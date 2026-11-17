package com.sahabatmobile.chat

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Video YouTube dari hasil pencarian publik (tanpa API key). */
data class YouTubeVideo(
    val id: String,
    val title: String,
    val channel: String,
    val duration: String = "",
    val url: String = "https://www.youtube.com/watch?v=$id",
    val thumb: String = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
)

object YouTubeFeed {

    private fun unescape(s: String): String = s
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003f", "?")
        .replace("\\u0025", "%")
        .replace("\\\"", "\"")
        .replace("\\n", " ")
        .trim()

    /** Cari video YouTube untuk sebuah topik. */
    fun search(query: String, limit: Int = 6): List<YouTubeVideo> {
        val found = fetchOne(query, limit)
        if (found.size >= 6) return found
        val alt = fetchOne("$query Indonesia", limit)
        val merged = (found + alt).distinctBy { it.id }
        return if (merged.size >= found.size) merged.take(limit) else found
    }

    private fun fetchOne(query: String, limit: Int): List<YouTubeVideo> {
        if (query.isBlank()) return emptyList()
        val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/129.0 Safari/537.36"
        )
        conn.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        val html = try {
            if (conn.responseCode != 200) return emptyList()
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return emptyList()
        } finally {
            conn.disconnect()
        }

        // Beberapa varian halaman memakai JSON dengan quote ter-escape, ada yang polos.
        val clean = html.replace("\\\"", "\"")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
        val idRegex = Regex(""""videoRenderer"\s*:\s*\{\s*"videoId"\s*:\s*"([\w-]{11})"""")
        val titleRegex = Regex(""""title"\s*:\s*\{\s*"runs"\s*:\s*\[\s*\{\s*"text"\s*:\s*"([^"]+)"""")
        val channelRegex = Regex(""""longBylineText"\s*:\s*\{\s*"runs"\s*:\s*\[\s*\{\s*"text"\s*:\s*"([^"]+)"""")
        val durationRegex = Regex(""""lengthText"\s*:\s*\{\s*"accessibility"\s*:\s*\{\s*"accessibilityData"\s*:\s*\{\s*"label"\s*:\s*"([^"]+)"""")

        val out = mutableListOf<YouTubeVideo>()
        for (m in idRegex.findAll(clean)) {
            if (out.size >= limit) break
            val id = m.groupValues[1]
            val chunk = clean.substring(m.range.first, minOf(clean.length, m.range.first + 3000))
            val title = titleRegex.find(chunk)?.groupValues?.get(1).orEmpty()
            val channel = channelRegex.find(chunk)?.groupValues?.get(1).orEmpty()
            val duration = durationRegex.find(chunk)?.groupValues?.get(1).orEmpty()
            if (title.isNotBlank()) {
                out.add(YouTubeVideo(id, unescape(title), unescape(channel), unescape(duration)))
            }
        }
        return out.distinctBy { it.id }
    }
}
