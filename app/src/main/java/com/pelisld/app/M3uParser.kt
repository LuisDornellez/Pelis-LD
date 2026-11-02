package com.pelisld.app

import java.io.BufferedReader
import java.net.URI

/** Parser de listas M3U/M3U8 de canales (#EXTINF + URL). */
object M3uParser {

    private const val MAX_ENTRIES = 100000
    private val GROUP_REGEX = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /** true si el texto es un manifiesto HLS (un solo stream) y no una lista de canales. */
    fun isHlsMediaPlaylist(text: String): Boolean =
        text.contains("#EXT-X-STREAM-INF") || text.contains("#EXT-X-TARGETDURATION")

    fun parse(reader: BufferedReader, baseUrl: String? = null): List<PlayItem> {
        val result = ArrayList<PlayItem>()
        var pendingTitle: String? = null
        var pendingGroup = ""
        var extGroup = ""

        while (result.size < MAX_ENTRIES) {
            val raw = reader.readLine() ?: break
            val line = raw.removePrefix("\uFEFF").trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingTitle = extractTitle(line)
                    pendingGroup = GROUP_REGEX.find(line)?.groupValues?.get(1)?.trim().orEmpty()
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    extGroup = line.substringAfter(':').trim()
                }
                line.startsWith("#") -> Unit
                else -> {
                    val url = resolve(line, baseUrl)
                    if (url != null) {
                        val title = pendingTitle?.takeIf { it.isNotBlank() }
                            ?: url.substringAfterLast('/').substringBefore('?').ifBlank { url }
                        val group = pendingGroup.ifEmpty { extGroup }
                        result.add(PlayItem(title, url, group))
                    }
                    pendingTitle = null
                    pendingGroup = ""
                    extGroup = ""
                }
            }
        }
        return result
    }

    /** El título es lo que sigue a la primera coma fuera de comillas. */
    private fun extractTitle(line: String): String {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') {
                inQuotes = !inQuotes
            } else if (c == ',' && !inQuotes) {
                return line.substring(i + 1).trim()
            }
        }
        return ""
    }

    private fun resolve(line: String, baseUrl: String?): String? {
        if (SCHEME_REGEX.containsMatchIn(line)) return line
        if (baseUrl == null) return null
        return try {
            URI(baseUrl).resolve(line).toString()
        } catch (e: Exception) {
            null
        }
    }
}
