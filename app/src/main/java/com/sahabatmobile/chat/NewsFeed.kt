package com.sahabatmobile.chat

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NewsItem(
    val title: String,
    val source: String,
    val link: String,
    val published: Long,
    val timeAgo: String,
    val realLink: String = "",
    val imageUrl: String = ""
)

object NewsFeed {

    private const val TAG = "SM_NEWS"
    private const val CACHE_TTL = 15 * 60 * 1000L
    private const val ANTARA = "https://www.antaranews.com/rss/terkini.xml"
    private val RSS_DATE = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
    private val DAY_DATE = SimpleDateFormat("d MMM", Locale("id", "ID"))

    private var cacheKey = ""
    private var cacheAt = 0L
    private var cacheItems = emptyList<NewsItem>()
    private val imageCache = mutableMapOf<String, String>()

    val TOPIC_LABEL = mapOf(
        "saham" to "Saham & Investasi",
        "seller" to "Seller & Bisnis",
        "umum" to "Info Terkini"
    )

    private val TOPIC_QUERY = mapOf(
        "saham" to "IHSG saham Indonesia bursa efek",
        "seller" to "seller marketplace penjualan online Indonesia",
        "umum" to "berita Indonesia terkini teknologi bisnis"
    )

    fun detectTopic(vararg fields: String?): String {
        val text = fields.filterNotNull().joinToString(" ").lowercase(Locale.getDefault())
        return when {
            Regex("saham|investor|trading|bursa|ihsg|forex|kripto|analis keuangan|pasar modal")
                .containsMatchIn(text) -> "saham"
            Regex("seller|jualan|toko|marketplace|shopee|tokopedia|bisnis|wirausaha|online shop|e-commerce|penjualan")
                .containsMatchIn(text) -> "seller"
            else -> "umum"
        }
    }

    fun label(topic: String): String = TOPIC_LABEL[topic] ?: TOPIC_LABEL.getValue("umum")

    private const val NEWS_PREFS = "sm_news_cache"

    fun saveToPrefs(context: Context, topic: String, items: List<NewsItem>) {
        val arr = JSONArray()
        items.forEach { n ->
            arr.put(JSONObject()
                .put("title", n.title)
                .put("source", n.source)
                .put("link", n.link)
                .put("published", n.published)
                .put("imageUrl", n.imageUrl))
        }
        context.getSharedPreferences(NEWS_PREFS, Context.MODE_PRIVATE).edit()
            .putString(topic, arr.toString())
            .putLong("${topic}_ts", System.currentTimeMillis())
            .apply()
    }

    fun loadFromPrefs(context: Context, topic: String, maxAgeMs: Long = 12 * 60 * 60 * 1000L): List<NewsItem> {
        val p = context.getSharedPreferences(NEWS_PREFS, Context.MODE_PRIVATE)
        val ts = p.getLong("${topic}_ts", 0L)
        if (ts <= 0L || System.currentTimeMillis() - ts > maxAgeMs) return emptyList()
        val raw = p.getString(topic, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val published = o.optLong("published", 0L)
                NewsItem(
                    title = o.optString("title"),
                    source = o.optString("source"),
                    link = o.optString("link"),
                    published = published,
                    timeAgo = timeAgo(published),
                    imageUrl = o.optString("imageUrl")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fetch(topic: String, limit: Int = 4): List<NewsItem> =
        fetchByQuery(TOPIC_QUERY[topic] ?: TOPIC_QUERY.getValue("umum"), limit)

    @Synchronized
    fun fetchByQuery(query: String, limit: Int = 5): List<NewsItem> {
        if (query.isBlank()) return emptyList()
        if (cacheKey == query && System.currentTimeMillis() - cacheAt < CACHE_TTL && cacheItems.isNotEmpty()) {
            return cacheItems.take(limit)
        }

        val googleUrl = "https://news.google.com/rss/search?q=" +
            URLEncoder.encode(query, "UTF-8") + "&hl=id&gl=ID&ceid=ID:id"
        var items = readRss(googleUrl)
        if (items.isEmpty()) {
            android.util.Log.i(TAG, "fallback ke ANTARA")
            items = readRss(ANTARA)
            val regex = when {
                Regex("(?i)saham|ihsg|bursa").containsMatchIn(query) ->
                    Regex("(?i)saham|ihsg|bursa|emiten|investasi|pasar modal|idr|bank")
                Regex("(?i)seller|marketplace|jualan").containsMatchIn(query) ->
                    Regex("(?i)seller|marketplace|jualan|e-commerce|penjualan|umkm|toko")
                else -> null
            }
            if (regex != null) {
                val matched = items.filter { regex.containsMatchIn(it.title) }
                items = (matched + items).distinctBy { it.link }.take(limit)
            }
        }

        cacheKey = query
        cacheAt = System.currentTimeMillis()
        cacheItems = items
        return items.take(limit)
    }

    private fun readRss(urlText: String): List<NewsItem> {
        val conn = URL(urlText).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) SahabatMobile/1.1.0")
        val items = mutableListOf<NewsItem>()
        try {
            if (conn.responseCode != 200) {
                android.util.Log.w(TAG, "HTTP ${conn.responseCode} untuk $urlText")
                return emptyList()
            }
            val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(sanitize(raw)))
            var event = parser.eventType
            var inItem = false
            var tag = ""
            var title = ""
            var source = ""
            var link = ""
            var pubDate = ""
            var description = ""
            var imageUrl = ""
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        tag = parser.name
                        if (tag == "item") {
                            inItem = true
                            title = ""; source = ""; link = ""; pubDate = ""
                            description = ""; imageUrl = ""
                        } else if (inItem && tag.substringAfter(':') in setOf("content", "thumbnail", "enclosure")) {
                            val attr = parser.getAttributeValue(null, "url").orEmpty()
                            if (attr.startsWith("http") && imageUrl.isBlank()) imageUrl = attr
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (inItem) {
                            val text = parser.text?.trim().orEmpty()
                            if (text.isNotEmpty()) when (tag) {
                                "title" -> if (title.isEmpty()) title = text
                                "source" -> if (source.isEmpty()) source = text
                                "link" -> if (link.isEmpty()) link = text
                                "pubDate" -> if (pubDate.isEmpty()) pubDate = text
                                "description" -> if (description.isEmpty()) description = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item" && inItem) {
                            val cleanTitle = if (source.isNotBlank() && title.endsWith(" - $source"))
                                title.removeSuffix(" - $source")
                            else title
                            val published = try {
                                RSS_DATE.parse(pubDate)?.time ?: 0L
                            } catch (e: Exception) {
                                0L
                            }
                            if (cleanTitle.isNotBlank() && link.isNotBlank()) {
                                val fromDescription = Regex("""(?is)<img[^>]+src=["']([^"']+)["']""")
                                    .find(description)?.groupValues?.get(1).orEmpty()
                                items.add(
                                    NewsItem(
                                        cleanTitle,
                                        source.ifBlank { "ANTARA" },
                                        link,
                                        published,
                                        timeAgo(published),
                                        imageUrl = imageUrl.ifBlank { fromDescription }
                                            .replace("&amp;", "&")
                                    )
                                )
                            }
                            inItem = false
                        }
                        tag = ""
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "gagal memuat RSS: ${e.message}")
        } finally {
            conn.disconnect()
        }
        return items.sortedByDescending { it.published }.take(20)
    }

    /** Ambil isi artikel (dari meta description / tag article) untuk dijadikan konteks AI. */
    fun fetchArticleText(link: String, maxChars: Int = 5000): String {
        val url = if (link.contains("news.google.com")) resolveLink(link) else link
        if (url.isBlank()) return ""
        val html = get(url, false)
        if (html.isBlank()) return ""
        val meta = Regex("""(?is)<meta[^>]+(?:name|property)=["'](?:description|og:description)["'][^>]+content=["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1).orEmpty()
        val raw = Regex("""(?is)<article[^>]*>(.*?)</article>""")
            .find(html)?.groupValues?.get(1) ?: html
        var text = raw
            .replace(Regex("""(?is)<script.*?</script>"""), " ")
            .replace(Regex("""(?is)<style.*?</style>"""), " ")
            .replace(Regex("""(?is)<[^>]+>"""), " ")
            .replace(Regex("""&nbsp;|&#160;"""), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""&quot;|&#39;|&apos;"""), "\"")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (text.length < 200 && meta.isNotBlank()) text = "$meta $text".trim()
        return text.take(maxChars)
    }

    /** Ubah link perantara Google News menjadi URL artikel penerbit aslinya. */
    fun resolveLink(url: String): String {
        if (!url.contains("news.google.com") || !url.contains("/articles/")) return url
        val id = url.substringAfter("/articles/").substringBefore('?').substringBefore('/')
        if (id.isBlank()) return url
        try {
            val page = get("https://news.google.com/articles/$id", true)
            val sg = Regex("""data-n-a-sg="([^"]+)"""").find(page)?.groupValues?.get(1)
                ?: return url
            val ts = Regex("""data-n-a-ts="([^"]+)"""").find(page)?.groupValues?.get(1)
                ?: return url

            val ctx = JSONArray()
                .put(JSONArray()
                    .put("X").put("X")
                    .put(JSONArray().put("X").put("X"))
                    .put(JSONObject.NULL).put(JSONObject.NULL)
                    .put(1).put(1).put("US:en").put(JSONObject.NULL).put(1)
                    .put(JSONObject.NULL).put(JSONObject.NULL)
                    .put(JSONObject.NULL).put(JSONObject.NULL).put(JSONObject.NULL)
                    .put(0).put(1))
                .put("X").put("X").put(1)
                .put(JSONArray().put(1).put(1).put(1))
                .put(1).put(1).put(JSONObject.NULL).put(0).put(0)
                .put(JSONObject.NULL).put(0)
            val inner = JSONArray()
                .put("garturlreq").put(ctx).put(id)
                .put(ts.toLongOrNull() ?: ts).put(sg)
                .toString()
            val outer = JSONArray().put(JSONArray().put(JSONArray().put("Fbv4je").put(inner))).toString()
            val body = "f.req=" + URLEncoder.encode(outer, "UTF-8")
            val resp = post("https://news.google.com/_/DotsSplashUi/data/batchexecute", body)
            val at = resp.indexOf("garturlres")
            if (at >= 0) {
                val real = Regex("""https?://[^"\\ ]+""")
                    .find(resp.substring(at))?.value
                    ?.replace("\\u003d", "=")
                    ?.replace("\\u0026", "&")
                if (!real.isNullOrBlank()) return real
            }
        } catch (e: Exception) {
            Log.w(TAG, "gagal resolve link: ${e.message}")
        }
        return url
    }

    /** Ambil gambar kecil dari artikel sumber (og:image / twitter:image / img pertama). */
    fun resolveImage(item: NewsItem): String {
        if (item.imageUrl.isNotBlank()) return item.imageUrl
        imageCache[item.link]?.let { if (it.isNotBlank()) return it }
        val url = if (item.link.contains("news.google.com")) resolveLink(item.link) else item.link
        if (url.isBlank()) return ""
        val html = get(url, false)
        if (html.isBlank()) return ""
        val candidates = listOf(
            """(?is)<meta[^>]+(?:property|name)\s*=\s*["']og:image["'][^>]+content\s*=\s*["']([^"']+)["']""",
            """(?is)<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+(?:property|name)\s*=\s*["']og:image["']""",
            """(?is)<meta[^>]+(?:property|name)\s*=\s*["']twitter:image["'][^>]+content\s*=\s*["']([^"']+)["']""",
            """(?is)<img[^>]+src=["']([^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']"""
        )
        val img = candidates.mapNotNull { Regex(it).find(html)?.groupValues?.get(1) }
            .firstOrNull { it.startsWith("http") }
            .orEmpty()
            .replace("&amp;", "&")
        if (img.isNotBlank()) imageCache[item.link] = img
        return img
    }

    private fun get(urlText: String, consent: Boolean): String {
        val conn = URL(urlText).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/129.0 Safari/537.36")
        conn.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        if (consent) conn.setRequestProperty("Cookie", "CONSENT=PENDING+987")
        return try {
            if (conn.responseCode != 200) "" else
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (e: Exception) {
            ""
        } finally {
            conn.disconnect()
        }
    }

    private fun post(urlText: String, body: String): String {
        val conn = URL(urlText).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/129.0 Safari/537.36")
        conn.setRequestProperty("Origin", "https://news.google.com")
        conn.setRequestProperty("Referer", "https://news.google.com/")
        conn.setRequestProperty("X-Same-Domain", "1")
        conn.setRequestProperty("Cookie", "CONSENT=PENDING+987")
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) "" else
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (e: Exception) {
            ""
        } finally {
            conn.disconnect()
        }
    }

    private fun sanitize(s: String): String = s
        .replace(Regex("&ndash;|&#8211;"), "-")
        .replace(Regex("&mdash;|&#8212;"), "-")
        .replace(Regex("&rsquo;|&#8217;|&lsquo;|&#8216;"), "'")
        .replace(Regex("&ldquo;|&#8220;|&rdquo;|&#8221;"), "\"")
        .replace(Regex("&nbsp;|&#160;"), " ")
        .replace(Regex("&(?!#\\d+;|#x[0-9a-fA-F]+;|[a-zA-Z]+;)"), "&amp;")

    fun timeAgo(ts: Long): String {
        if (ts <= 0L) return ""
        val minutes = (System.currentTimeMillis() - ts) / 60_000L
        return when {
            minutes < 1 -> "baru saja"
            minutes < 60 -> "$minutes menit lalu"
            minutes < 1440 -> "${minutes / 60} jam lalu"
            minutes < 2880 -> "Kemarin"
            else -> DAY_DATE.format(Date(ts))
        }
    }
}
