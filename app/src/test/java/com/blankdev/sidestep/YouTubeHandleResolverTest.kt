package com.blankdev.sidestep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeHandleResolverTest {

    @Test
    fun extractHandle_musicHandleLinks() {
        assertEquals("xdinaryheroesofficial",
            YouTubeHandleResolver.extractHandle("https://music.youtube.com/@xdinaryheroesofficial"))
        assertEquals("xdinaryheroesofficial",
            YouTubeHandleResolver.extractHandle("https://music.youtube.com/@xdinaryheroesofficial?si=abc"))
        assertEquals("some.artist",
            YouTubeHandleResolver.extractHandle("https://music.youtube.com/@some.artist/featured"))
    }

    @Test
    fun extractHandle_ignoresOtherLinks() {
        assertNull(YouTubeHandleResolver.extractHandle("https://www.youtube.com/@xdinaryheroesofficial"))
        assertNull(YouTubeHandleResolver.extractHandle("https://music.youtube.com/channel/UCabcdefghijklmnopqrstuv"))
        assertNull(YouTubeHandleResolver.extractHandle("https://music.youtube.com/watch?v=abc"))
        assertNull(YouTubeHandleResolver.extractHandle("https://music.youtube.com/@"))
    }

    @Test
    fun parseResolveResponse_browseEndpoint() {
        val json = """{"endpoint":{"clickTrackingParams":"x","commandMetadata":{"webCommandMetadata":{"url":"/@x","webPageType":"WEB_PAGE_TYPE_CHANNEL"}},"browseEndpoint":{"browseId":"UC1234567890abcdefghij_-","canonicalBaseUrl":"/@x"}}}"""
        assertEquals("UC1234567890abcdefghij_-", YouTubeHandleResolver.parseResolveResponse(json))
    }

    @Test
    fun parseResolveResponse_invalid() {
        assertNull(YouTubeHandleResolver.parseResolveResponse("""{"error":{"code":400}}"""))
        assertNull(YouTubeHandleResolver.parseResolveResponse("""{"endpoint":{"browseEndpoint":{"browseId":"FEwhat_to_watch"}}}"""))
        assertNull(YouTubeHandleResolver.parseResolveResponse("not json"))
    }

    @Test
    fun parseChannelIdFromHtml() {
        val html = """<html><head><link rel="canonical" href="https://www.youtube.com/channel/UC1234567890abcdefghij_-"></head></html>"""
        assertEquals("UC1234567890abcdefghij_-", YouTubeHandleResolver.parseChannelIdFromHtml(html))
        val html2 = """var ytInitialData = {"metadata":{"channelMetadataRenderer":{"externalId":"UCzyxwvutsrqponmlkjihgfe"}}};"""
        assertEquals("UCzyxwvutsrqponmlkjihgfe", YouTubeHandleResolver.parseChannelIdFromHtml(html2))
        assertNull(YouTubeHandleResolver.parseChannelIdFromHtml("<html>consent</html>"))
    }

    @Test
    fun buildsAppFriendlyUrls() {
        assertEquals("https://music.youtube.com/channel/UC1234567890abcdefghij_-",
            YouTubeHandleResolver.channelUrl("UC1234567890abcdefghij_-"))
        assertEquals("https://music.youtube.com/search?q=some+artist",
            YouTubeHandleResolver.searchUrl("some artist"))
    }
}
