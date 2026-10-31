package com.blankdev.sidestep

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import androidx.core.net.toUri
import java.net.URI
import java.net.URISyntaxException

/**
 * Lets Sidestep be set as the default browser app, so it sees every link, including links that
 * apps send straight to "the browser" (Custom Tabs / in-app browsers) without Android's per-domain
 * link handling ever asking Sidestep.
 *
 * Links Sidestep handles are processed as usual. Everything else is forwarded untouched
 * (same intent, extras included, so Custom Tabs keep working) to a real browser.
 */
object BrowserPassthrough {

    private const val TAG = "BrowserPassthrough"

    /** Domains Sidestep processes (subdomains included). Mirrors the AndroidManifest hosts. */
    private val HANDLED_DOMAINS = listOf(
        "twitter.com", "x.com",
        "tiktok.com",
        "reddit.com", "redd.it",
        "youtube.com", "youtu.be",
        "imdb.com", "medium.com", "wikipedia.org", "goodreads.com", "genius.com",
        "github.com", "stackoverflow.com", "instagram.com", "nytimes.com", "tumblr.com",
        "urbandictionary.com", "imgur.com", "fandom.com",
        "pixiv.net", "pixiv.me",
        "share.google", "msn.com", "apple.news", "news.apple.com",
        "maps.google.com"
    )

    /** Preferred browsers to forward to, in order, if installed. Otherwise any browser is used. */
    private val PREFERRED_BROWSERS = listOf(
        "org.mozilla.firefox", "org.mozilla.fenix", "org.mozilla.firefox_beta",
        "org.mozilla.focus", "org.mozilla.klar", "org.ironfoxoss.ironfox", "io.github.forkmaintainers.iceraven"
    )

    private fun host(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (e: URISyntaxException) {
        null
    }

    private fun matchesDomain(host: String, domain: String) =
        host == domain || host.endsWith(".$domain")

    /** Whether Sidestep should process [url] itself rather than pass it straight to a browser. */
    fun shouldHandle(context: Context, url: String): Boolean {
        val host = host(url) ?: return false
        if (UrlCleaner.isPlayStoreAppUrl(url)) return true
        if (HANDLED_DOMAINS.any { matchesDomain(host, it) }) return true
        return SettingsUtils.getCustomRedirects(context).any { redirect ->
            redirect.isEnabled && redirect.originalDomain.isNotBlank() &&
                matchesDomain(host, redirect.originalDomain.lowercase().removePrefix("www."))
        }
    }

    /** Installed web browsers other than Sidestep, best first. */
    fun browsers(context: Context): List<ResolveInfo> {
        val generic = Intent(Intent.ACTION_VIEW, "https://example.com/".toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val all = context.packageManager
            .queryIntentActivities(generic, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
        return all.sortedBy { info ->
            PREFERRED_BROWSERS.indexOf(info.activityInfo.packageName).let { if (it < 0) Int.MAX_VALUE else it }
        }
    }

    /**
     * Picks who should open [url] when Sidestep hands it on: an app that specifically handles the
     * link (not a browser, not Sidestep) if there is one, otherwise the preferred browser.
     */
    fun pickExternalHandler(context: Context, url: String): ResolveInfo? {
        val browserList = browsers(context)
        val browserPkgs = browserList.map { it.activityInfo.packageName }.toSet()
        val viewIntent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        val specific = context.packageManager
            .queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull {
                val pkg = it.activityInfo.packageName
                pkg != context.packageName && pkg !in browserPkgs
            }
        return specific ?: browserList.firstOrNull()
    }

    /** Forwards the original VIEW [intent] unchanged to a real browser. */
    fun forwardToBrowser(context: Context, intent: Intent): Boolean {
        val browser = browsers(context).firstOrNull() ?: return false
        val forward = Intent(intent).apply {
            component = null
            setClassName(browser.activityInfo.packageName, browser.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(forward)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Could not forward to ${browser.activityInfo.packageName}", e)
            false
        }
    }
}
