package com.sahabatmobile.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

data class ShopeeProduct(
    val title: String,
    val url: String,
    val image: String? = null,
    val rating: Double? = null
)

object ShopeeSearch {

    private const val TAG = "SM_SHOPEE"
    private const val MIN_GAP_MS = 2500L
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private val cacheLock = Any()
    private val cache = mutableMapOf<String, Pair<Long, List<ShopeeProduct>>>()
    @Volatile private var lastRequestAt = 0L

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun search(keyword: String, limit: Int = 5): List<ShopeeProduct> {
        if (keyword.isBlank()) return emptyList()
        val cacheKey = keyword.trim().lowercase()
        synchronized(cacheLock) {
            cache[cacheKey]?.let { (ts, list) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                    android.util.Log.i(TAG, "cache hit: $cacheKey")
                    return list.take(limit)
                }
            }
        }

        val gap = System.currentTimeMillis() - lastRequestAt
        if (gap < MIN_GAP_MS) {
            try { Thread.sleep(MIN_GAP_MS - gap) } catch (e: Exception) {}
        }
        lastRequestAt = System.currentTimeMillis()

        val fromImages = try { searchProducts(keyword, limit) } catch (e: Exception) { emptyList() }

        var raw = searchWeb(keyword, limit * 2)
        var attempt = 1
        while (raw.isEmpty() && attempt < 3) {
            try { Thread.sleep(2000L * attempt) } catch (e: Exception) {}
            lastRequestAt = System.currentTimeMillis()
            raw = searchWeb(keyword, limit * 2)
            attempt++
        }
        if (raw.isEmpty() && fromImages.isEmpty()) return withImages(fallbackSuggestions(keyword, limit), keyword)

        val productPages = raw.filter { Regex("-i\\.\\d+\\.\\d+").containsMatchIn(it.url) }
        val otherPages = raw.filter { !Regex("-i\\.\\d+\\.\\d+").containsMatchIn(it.url) }
        val webPicked = (productPages + otherPages).take(limit + 3)
        val merged = (fromImages + webPicked).distinctBy { it.url.substringBefore('?') }

        // Produk sudah dihapus / tokonya tidak aktif -> saring, walau ratingnya tinggi
        val productRx = Regex("-i\\.\\d+\\.\\d+")
        val itemRx = Regex("\"itemid\"\\s*:\\s*\\d+")
        val result = mutableListOf<ShopeeProduct>()
        for (p in merged) {
            if (result.size >= limit) break
            val html = fetchHtml(p.url)
            if (productRx.containsMatchIn(p.url) && html != null && !itemRx.containsMatchIn(html)) {
                android.util.Log.i(TAG, "lewati tidak-aktif: ...${p.url.takeLast(45)}")
                continue
            }
            if (!p.image.isNullOrEmpty()) {
                result.add(p.copy(rating = parseSellerRating(html)))
            } else {
                val og = parseOg(html)
                var title = og?.first?.takeIf { it.isNotBlank() } ?: p.title
                title = cleanTitle(title, p.url)
                result.add(
                    ShopeeProduct(
                        title = title,
                        url = p.url,
                        image = og?.second,
                        rating = parseSellerRating(html)
                    )
                )
            }
        }
        if (result.isEmpty()) return withImages(fallbackSuggestions(keyword, limit), keyword)
        val sorted = withImages(result, keyword).sortedWith(compareByDescending { it.rating ?: 0.0 })
        synchronized(cacheLock) {
            cache[cacheKey] = System.currentTimeMillis() to sorted
        }
        return sorted
    }

    /** Lengkapi produk yang belum punya gambar, agar kartu selalu menampilkan gambar. */
    private fun withImages(products: List<ShopeeProduct>, keyword: String): List<ShopeeProduct> {
        if (products.isEmpty()) return products
        if (products.all { !it.image.isNullOrEmpty() }) return products
        val imgs = try { searchImages(keyword, products.size) } catch (e: Exception) { emptyList() }
        if (imgs.isEmpty()) return products
        var idx = 0
        return products.map { p ->
            if (p.image.isNullOrEmpty() && idx < imgs.size) p.copy(image = imgs[idx++]) else p
        }
    }

    /** Cari gambar produk via Bing Images (halaman Shopee tidak menyediakan og:image). */
    private fun searchImages(keyword: String, count: Int): List<String> {
        val q = URLEncoder.encode("$keyword shopee", "UTF-8")
        val html = try {
            httpGet("https://www.bing.com/images/search?q=$q&form=HDRSC2&first=1")
        } catch (e: Exception) { null } ?: return emptyList()
        val esc = Regex("&quot;murl&quot;:&quot;(.*?)&quot;")
        val raw = Regex("\"murl\":\"(.*?)\"")
        val out = LinkedHashSet<String>()
        for (m in esc.findAll(html) + raw.findAll(html)) {
            val u = m.groupValues[1].replace("\\/", "/").replace("&amp;", "&")
            if (u.startsWith("http")) out.add(u)
            if (out.size >= count) break
        }
        android.util.Log.i(TAG, "images=${out.size} for=$keyword")
        return out.toList()
    }

    /** Produk asli dari Bing Images: purl (halaman produk Shopee) + murl (gambar), berpasangan per blok. */
    private fun searchProducts(keyword: String, limit: Int): List<ShopeeProduct> {
        val q = URLEncoder.encode("$keyword shopee", "UTF-8")
        val html = try {
            httpGet("https://www.bing.com/images/search?q=$q&form=HDRSC2")
        } catch (e: Exception) { null } ?: return emptyList()
        val blocks = Regex("<a[^>]*class=\"iusc\"[^>]*\\sm=\"([^\"]+)\"").findAll(html).toList()
        val rxP = Regex("&quot;purl&quot;:&quot;(.*?)&quot;")
        val rxM = Regex("&quot;murl&quot;:&quot;(.*?)&quot;")
        val isProduct = Regex("-i\\.\\d+\\.\\d+")
        val out = mutableListOf<ShopeeProduct>()
        val seen = mutableSetOf<String>()
        for (b in blocks) {
            val attr = b.groupValues[1]
            val purl = rxP.find(attr)?.groupValues?.get(1)?.replace("&amp;", "&")
            val murl = rxM.find(attr)?.groupValues?.get(1)?.replace("&amp;", "&")
            if (purl == null || !purl.contains("shopee.co.id")) continue
            if (!isProduct.containsMatchIn(purl)) continue
            if (purl.contains("/help.") || purl.contains("/inspirasi") || purl.contains("shopee.co.id/m/")) continue
            val key = purl.substringBefore('?')
            if (!seen.add(key)) continue
            val title = slugToTitle(purl)
            if (title.length < 6) continue
            out.add(ShopeeProduct(title = title, url = purl, image = murl))
            if (out.size >= limit) break
        }
        android.util.Log.i(TAG, "produkGambar=${out.size} blok=${blocks.size}")
        return out
    }

    /** Ubah slug URL produk Shopee menjadi judul yang enak dibaca. */
    private fun slugToTitle(url: String): String {
        var slug = url.substringAfter("shopee.co.id/").substringBefore('?')
        if (slug.endsWith("/")) slug = slug.trimEnd('/')
        slug = slug.substringBeforeLast("-i.")
        val words = slug.split('-', '/').filter { it.isNotBlank() }
        var t = words.joinToString(" ") { w ->
            if (w.length <= 3 && w == w.uppercase()) w else w.lowercase().replaceFirstChar { it.uppercase() }
        }.trim()
        if (t.length > 70) {
            val cut = t.substring(0, 70)
            t = cut.substringBeforeLast(' ') + "..."
        }
        return t
    }

    private fun cleanTitle(raw: String, url: String): String {
        var t = raw
            .replace(Regex("\\s*\\|\\s*Shopee( Indonesia)?$"), "")
            .replace(Regex("\\s*Terlengkap( & Harga Terbaru.*)?$"), "")
            .replace(Regex("\\s*Harga Terbaru.*$"), "")
            .trim()
        val generic = t.isEmpty() ||
            t.equals("Shopee Indonesia", true) ||
            t.contains("Situs Belanja", true) ||
            t.matches(Regex(".*shopee\\.co\\.id.*"))
        if (generic) {
            var slug = url.substringAfter("shopee.co.id/").substringBefore('?')
            if (slug.endsWith("/")) slug = slug.trimEnd('/')
            slug = slug.substringBeforeLast("-i.")
            t = slug.split('-').filter { it.isNotBlank() }.joinToString(" ") { w ->
                if (w.length <= 3 && w == w.uppercase()) w else w.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
        return t.ifBlank { raw }
    }

    private fun fallbackSuggestions(keyword: String, limit: Int): List<ShopeeProduct> {
        val kw = keyword.trim()
        val suffixes = listOf("", " murah", " original", " terbaru", " terlaris")
        return suffixes.take(limit).map { sfx ->
            val label = (kw + sfx).trim()
            ShopeeProduct(
                title = "Cari \"$label\" di Shopee",
                url = "https://shopee.co.id/search?keyword=" + URLEncoder.encode(label, "UTF-8")
            )
        }
    }

    private fun searchWeb(keyword: String, limit: Int): List<ShopeeProduct> {
        val q = URLEncoder.encode("site:shopee.co.id $keyword", "UTF-8")
        var lite: String? = null
        try {
            lite = httpGet("https://lite.duckduckgo.com/lite/?q=$q&kl=id-id")
            android.util.Log.i(TAG, "lite code-ok len=${lite?.length ?: -1}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "lite failed: ${e.message}")
        }
        var out = if (!lite.isNullOrEmpty()) extractResults(lite, limit) else emptyList()
        if (out.isEmpty()) {
            try {
                val post = httpPost("https://html.duckduckgo.com/html/", "q=$q&kl=id-id")
                android.util.Log.i(TAG, "htmlPost len=${post?.length ?: -1}")
                if (!post.isNullOrEmpty()) out = extractResults(post, limit)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "htmlPost failed: ${e.message}")
            }
        }
        if (out.isEmpty()) {
            try {
                val bing = httpGet("https://www.bing.com/search?q=$q&setlang=id&count=30")
                android.util.Log.i(TAG, "bing len=${bing?.length ?: -1}")
                if (!bing.isNullOrEmpty()) out = extractResults(bing, limit)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "bing failed: ${e.message}")
            }
        }
        if (out.isEmpty()) android.util.Log.e(TAG, "SEMUA engine gagal/parse=0")
        return out
    }

    private fun extractResults(html: String, limit: Int): List<ShopeeProduct> {
        val norm = html.replace(Regex("""class\s*=\s*'([^']*)'"""), """class="$1"""")
        val best = LinkedHashMap<String, String>()
        val clean = Regex("<[^>]+>")
        // Anchor generik: berlaku untuk DuckDuckGo (href uddg=) maupun Bing (href literal)
        val anchors = Regex("""<a\s[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL))
        for (m in anchors.findAll(norm)) {
            val url = extractUrl(m.groupValues[1]) ?: continue
            if (!url.contains("shopee.co.id")) continue
            if (url.contains("/help.") || url.contains("/inspirasi") ||
                url.contains("shopee.co.id/m/") || url.contains("shopee.co.id/search")
            ) continue
            val title = m.groupValues[2]
                .replace(clean, " ")
                .replace("&amp;", "&")
                .replace("&#x27;", "'")
                .replace("&quot;", "\"")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (title.length < 6) continue
            val key = url.substringBefore('?')
            val prev = best[key]
            if (prev == null) best[key] = title else if (title.length > prev.length) best[key] = title
            if (best.size >= limit * 4) break
        }
        val out = best.entries.take(limit).map { ShopeeProduct(it.value, it.key) }
        android.util.Log.i(TAG, "hasil=${out.size} contoh=${out.firstOrNull()?.title}")
        return out
    }

    fun fetchOgMeta(url: String): Pair<String?, String?>? {
        val html = try { httpGet(url) } catch (e: Exception) { null } ?: return null
        val title = Regex("property=\"og:title\"[^>]*content=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
            ?: Regex("content=\"([^\"]*)\"[^>]*property=\"og:title\"").find(html)?.groupValues?.get(1)
        val img = Regex("property=\"og:image\"[^>]*content=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
            ?: Regex("content=\"([^\"]*)\"[^>]*property=\"og:image\"").find(html)?.groupValues?.get(1)
        return title?.replace("&amp;", "&") to img
    }

    private fun fetchHtml(url: String): String? {
        return try { httpGet(url) } catch (e: Exception) { null }
    }

    private fun parseOg(html: String?): Pair<String?, String?>? {
        if (html == null) return null
        val title = Regex("property=\"og:title\"[^>]*content=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
            ?: Regex("content=\"([^\"]*)\"[^>]*property=\"og:title\"").find(html)?.groupValues?.get(1)
        val img = Regex("property=\"og:image\"[^>]*content=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
            ?: Regex("content=\"([^\"]*)\"[^>]*property=\"og:image\"").find(html)?.groupValues?.get(1)
        return title?.replace("&amp;", "&") to img
    }

    private fun parseSellerRating(html: String?): Double? {
        val patterns = listOf(
            Regex("\"rating_star\"\\s*:\\s*\"?([\\d.]+)\"?"),
            Regex("\"store_rate\"\\s*:\\s*\"?([\\d.]+)\"?"),
            Regex("\"rate\"\\s*:\\s*\"?([\\d.]+)\"?"),
            Regex("\"rating\"\\s*:\\s*\"?([\\d.]+)\"?")
        )
        for (p in patterns) {
            val m = p.find(html ?: return null) ?: continue
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            if (v > 0) return v
        }
        return null
    }

    fun sellerRating(url: String): Double? {
        val html = try { httpGet(url) } catch (e: Exception) { return null }
        return parseSellerRating(html)
    }

    fun loadImage(url: String): Bitmap? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", UA)
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "id-ID,id;q=0.9")
        try {
            if (conn.responseCode != 200) return null
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(url: String, body: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "application/x-www.form-urlencoded")
        conn.setRequestProperty("User-Agent", UA)
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) return null
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractUrl(href: String): String? {
        if (href.contains("uddg=")) {
            val enc = href.substringAfter("uddg=").substringBefore("&")
            return try { URLDecoder.decode(enc, "UTF-8") } catch (e: Exception) { enc }
        }
        return if (href.startsWith("http")) href else null
    }

    fun openInShopee(context: Context, url: String) {
        val target = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        for (pkg in listOf("com.shopee.id", "com.shopee")) {
            try {
                context.startActivity(Intent(target).setPackage(pkg))
                return
            } catch (e: Exception) {
            }
        }
        try {
            context.startActivity(target)
        } catch (e: Exception) {
        }
    }
}
