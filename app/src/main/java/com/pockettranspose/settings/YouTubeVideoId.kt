package com.pockettranspose.settings

import java.net.URI

/** Extracts the 11-character YouTube video ID from a page URL, or null. */
object YouTubeVideoId {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val PATH_PREFIXES = listOf("/shorts/", "/live/", "/embed/", "/v/")

    fun from(url: String?): String? {
        val uri = runCatching { URI(url ?: return null) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.rawPath.orEmpty()
        val candidate = when {
            host == "youtu.be" -> path.removePrefix("/").substringBefore('/')
            !isYouTubeHost(host) -> null
            path == "/watch" -> queryParam(uri.rawQuery, "v")
            else -> PATH_PREFIXES.firstOrNull { path.startsWith(it) }
                ?.let { path.removePrefix(it).substringBefore('/') }
        }
        return candidate?.takeIf { ID.matches(it) }
    }

    private fun isYouTubeHost(host: String) =
        host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")

    private fun queryParam(query: String?, name: String): String? =
        query?.split('&')
            ?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
}
