package com.blankdev.sidestep

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/**
 * Converts YouTube Music handle links (music.youtube.com/@handle) into links that
 * third-party music apps understand (music.youtube.com/channel/UC...).
 *
 * Apps such as Meld only accept /watch, /playlist, /browse/, /channel/ and /c/ paths.
 * Without this, handle links fall back to the browser, where the YouTube Music web page
 * rewrites them into intent:// links pinned to the official YouTube Music app.
 */
object YouTubeHandleResolver {

    private const val TAG = "YouTubeHandleResolver"
    private const val RESOLVE_ENDPOINT =
        "https://www.youtube.com/youtubei/v1/navigation/resolve_url?prettyPrint=false"
    private const val WEB_CLIENT_VERSION = "2.20250101.00.00"
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // "Reject all" consent cookie: avoids the EU consent interstitial on youtube.com pages
    private const val CONSENT_COOKIE = "SOCS=CAI"

    private val CHANNEL_ID_REGEX = Regex("^UC[0-9A-Za-z_-]{22}$")
    private val HTML_PATTERNS = listOf(
        Regex("""<link rel="canonical" href="https://www\.youtube\.com/channel/(UC[0-9A-Za-z_-]{22})""""),
        Regex(""""externalId":"(UC[0-9A-Za-z_-]{22})""""),
        Regex("""<meta itemprop="identifier" content="(UC[0-9A-Za-z_-]{22})""""),
        Regex(""""channelId":"(UC[0-9A-Za-z_-]{22})"""")
    )

    /** Returns the handle (without "@") if [url] is a music.youtube.com/@handle link. */
    fun extractHandle(url: String): String? {
        if (!UrlCleaner.isYouTubeMusicUrl(url)) return null
        val path = try {
            URI(url).rawPath ?: return null
        } catch (e: java.net.URISyntaxException) {
            return null
        }
        val first = path.split("/").firstOrNull { it.isNotEmpty() } ?: return null
        if (!first.startsWith("@") || first.length < 2) return null
        return java.net.URLDecoder.decode(first.substring(1), "UTF-8")
    }

    /** Extracts the channel ID from a navigation/resolve_url response. */
    fun parseResolveResponse(json: String): String? {
        return try {
            val id = JSONObject(json)
                .optJSONObject("endpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId", "")
                .orEmpty()
            id.takeIf { CHANNEL_ID_REGEX.matches(it) }
        } catch (e: JSONException) {
            null
        }
    }

    /** Extracts the channel ID from a channel page's HTML. */
    fun parseChannelIdFromHtml(html: String): String? {
        for (pattern in HTML_PATTERNS) {
            pattern.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    fun channelUrl(channelId: String) = "https://music.youtube.com/channel/$channelId"

    fun searchUrl(handle: String) =
        "https://music.youtube.com/search?q=" + URLEncoder.encode(handle, "UTF-8")

    /**
     * If [url] is a music.youtube.com/@handle link, returns an app-friendly equivalent:
     * the /channel/UC... URL, or a search for the handle if the channel can't be resolved.
     * Any other URL is returned unchanged.
     */
    suspend fun toAppFriendlyUrl(url: String): String {
        val handle = extractHandle(url) ?: return url
        val channelId = withContext(Dispatchers.IO) {
            resolveViaApi(handle) ?: resolveViaHtml(handle)
        }
        return if (channelId != null) channelUrl(channelId) else searchUrl(handle)
    }

    private fun resolveViaApi(handle: String): String? {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", WEB_CLIENT_VERSION)
                .put("hl", "en")))
            .put("url", "https://www.youtube.com/@$handle")
            .toString()
        val request = Request.Builder()
            .url(RESOLVE_ENDPOINT)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", USER_AGENT)
            .header("Cookie", CONSENT_COOKIE)
            .build()
        return execute(request)?.let { parseResolveResponse(it) }
    }

    private fun resolveViaHtml(handle: String): String? {
        val request = Request.Builder()
            .url("https://www.youtube.com/@" + URLEncoder.encode(handle, "UTF-8"))
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en")
            .header("Cookie", CONSENT_COOKIE)
            .build()
        return execute(request)?.let { parseChannelIdFromHtml(it) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute(request: Request): String? {
        return try {
            NetworkClient.client.newBuilder()
                .followRedirects(true)
                .build()
                .newCall(request)
                .execute()
                .use { response -> if (response.isSuccessful) response.body.string() else null }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve ${request.url}", e)
            null
        }
    }
}
