package com.pelisld.app

import android.net.Uri

object UrlUtils {

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val VIDEO_PATTERN = Regex(
        "\\.(mp4|m3u8)(\\?|#|$)|googlevideo\\.com/videoplayback",
        RegexOption.IGNORE_CASE
    )

    fun isLikelyVideo(url: String): Boolean = VIDEO_PATTERN.containsMatchIn(url)

    fun isHls(url: String): Boolean = url.contains(".m3u8", ignoreCase = true)

    fun searchUrl(query: String): String = "https://www.google.com/search?q=" + Uri.encode(query)

    /** Si el texto no es una URL http(s), se trata como término de búsqueda. */
    fun toLoadableUrl(input: String): String {
        val text = input.trim()
        return if (text.startsWith("http://") || text.startsWith("https://")) text else searchUrl(text)
    }
}
