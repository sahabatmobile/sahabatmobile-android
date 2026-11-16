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

/**
 * Marketplace yang didukung pencarian produknya.
 * Daftar domain dipakai untuk menyaring hasil mesin pencari,
 * daftar paket aplikasi dipakai saat membuka produk.
 */
enum class Market(val label: String, val domains: List<String>, val apps: List<String>) {
    SHOPEE("Shopee", listOf("shopee.co.id", "shopee.com"), listOf("com.shopee.id", "com.shopee")),
    TOKOPEDIA("Tokopedia", listOf("tokopedia.com"), listOf("com.tokopedia.android")),
    BLIBLI("Blibli", listOf("blibli.com"), listOf("com.blibli")),
    LAZADA("Lazada", listOf("lazada.co.id"), listOf("com.lazada.android"));

    fun owns(url: String): Boolean = domains.any { url.contains(it) }

    /** URL halaman produk (bukan kategori/pencarian/landing page). */
    fun isProductUrl(url: String): Boolean = when (this) {
        SHOPEE -> Regex("-i\\.\\d+\\.\\d+").containsMatchIn(url) &&
                !url.contains("/help.") && !url.contains("/inspirasi") &&
                !url.contains("shopee.co.id/m/") && !url.contains("/search")

        TOKOPEDIA -> {
            val path = url.substringAfter("tokopedia.com/").substringBefore('?').trim('/')
            path.contains('/') &&
                    !Regex("^(find|p|promo|official|topads|cari|search|tag|kontak|about|help|masuk|daftar|cart|checkout|payment|info|apps|news|blog)/")
                        .containsMatchIn(path)
        }

        BLIBLI -> Regex("/p/[^/]+/ps--").containsMatchIn(url)

        LAZADA -> Regex("/products/.+-i\\d+\\.html").containsMatchIn(url)
    }
}

/** Produk marketplace yang siap ditampilkan di chat / papan. */
data class MarketProduct(
    val title: String,
    val url: String,
    val image: String? = null,
    val rating: Double? = null,
    val sold: Int = 0,
    val price: String = "",
    val market: Market = Market.SHOPEE
)

/** Hasil pembacaan halaman produk: harga, rating, terjual, dan status stok. */
data class ProductInfo(
    val price: String = "",
    val rating: Double = 0.0,
    val sold: Int = 0,
    val status: Int = MarketSearch.STATUS_UNKNOWN
)

object MarketSearch {

    private const val TAG = "SM_MARKET"
    private const val MIN_GAP_MS = 2500L
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private val cacheLock = Any()
    private val cache = mutableMapOf<String, Pair<Long, List<MarketProduct>>>()
    @Volatile private var lastRequestAt = 0L

    const val STATUS_UNKNOWN = -1
    const val STATUS_ALIVE = 0
    const val STATUS_DEAD = 1
    const val STATUS_SOLD_OUT = 2

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun marketOf(url: String): Market? = Market.values().firstOrNull { it.owns(url) }

    // ------------------------------------------------------------- pencarian

    /** Marketplace yang datanya bisa dimuat otomatis (Blibli dikunci Cloudflare). */
    val LOADABLE_MARKETS = listOf(Market.SHOPEE, Market.TOKOPEDIA, Market.LAZADA)

    /**
     * Cari di semua marketplace sekaligus (dipakai saat pengguna tidak menyebut
     * marketplace tertentu). Hasil digabung, urut per marketplace.
     */
    fun searchMulti(keyword: String, perMarket: Int = 2): List<MarketProduct> {
        val out = mutableListOf<MarketProduct>()
        for (m in LOADABLE_MARKETS) {
            out.addAll(try {
                search(m, keyword, perMarket)
            } catch (e: Exception) {
                emptyList()
            })
        }
        return out
    }

    /**
     * Lengkapi harga/rating/terjual tiap produk dengan membaca halaman produknya
     * (1 fetch per produk). Produk yang ternyata mati/stok habis dibuang dari hasil.
     */
    fun enrich(products: List<MarketProduct>, maxItems: Int = 6): List<MarketProduct> {
        val out = mutableListOf<MarketProduct>()
        for (p in products.take(maxItems)) {
            val hasInfo = p.price.isNotEmpty() || p.sold > 0 || (p.rating ?: 0.0) > 0.0
            if (hasInfo || p.market == Market.BLIBLI) {
                out.add(p)
                continue
            }
            val info = try {
                Thread.sleep(300)
                productInfo(p.market, p.url)
            } catch (e: Exception) {
                ProductInfo()
            }
            when (info.status) {
                STATUS_DEAD, STATUS_SOLD_OUT -> android.util.Log.i(
                    TAG, "buang (${info.status}): ${p.url.takeLast(45)}"
                )
                else -> out.add(
                    p.copy(
                        price = info.price,
                        rating = if (info.rating > 0) info.rating else p.rating,
                        sold = if (info.sold > 0) info.sold else p.sold
                    )
                )
            }
        }
        return out
    }

    /** Urut produk: rating tertinggi, lalu penjualan terbanyak. */
    fun sortByQuality(products: List<MarketProduct>): List<MarketProduct> =
        products.sortedWith(
            compareByDescending<MarketProduct> { it.rating ?: 0.0 }
                .thenByDescending { it.sold }
        )

    fun search(market: Market, keyword: String, limit: Int = 5): List<MarketProduct> {
        if (keyword.isBlank()) return emptyList()
        val cacheKey = "${market.name}|${keyword.trim().lowercase()}"
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
            try {
                Thread.sleep(MIN_GAP_MS - gap)
            } catch (e: Exception) {
            }
        }
        lastRequestAt = System.currentTimeMillis()

        val fromImages = try {
            searchProducts(keyword, limit, market)
        } catch (e: Exception) {
            emptyList()
        }

        // Cukup dari pencarian gambar? -> jangan buang waktu ke mesin pencari web.
        var raw = if (fromImages.size >= limit) emptyList() else searchWeb(keyword, limit * 2, market)
        var attempt = 1
        while (raw.isEmpty() && attempt < 2 && fromImages.size < limit) {
            try {
                Thread.sleep(2000L * attempt)
            } catch (e: Exception) {
            }
            lastRequestAt = System.currentTimeMillis()
            raw = searchWeb(keyword, limit * 2, market)
            attempt++
        }
        if (raw.isEmpty() && fromImages.isEmpty()) {
            return withImages(fallbackSuggestions(keyword, limit, market), keyword, market)
        }

        val productPages = raw.filter { market.isProductUrl(it.url) }
        val otherPages = raw.filter { !market.isProductUrl(it.url) }
        val webPicked = (productPages + otherPages).take(limit + 3)
        val merged = (fromImages + webPicked).distinctBy { it.url.substringBefore('?') }
            .map { it.copy(market = market) }
            .take(limit)

        val sorted = withImages(merged, keyword, market)
        synchronized(cacheLock) {
            cache[cacheKey] = System.currentTimeMillis() to sorted
        }
        return sorted
    }

    /** Lengkapi produk yang belum punya gambar, agar kartu selalu menampilkan gambar. */
    private fun withImages(
        products: List<MarketProduct>,
        keyword: String,
        market: Market
    ): List<MarketProduct> {
        if (products.isEmpty()) return products
        if (products.all { !it.image.isNullOrEmpty() }) return products
        val imgs = try {
            searchImages(keyword, products.size, market)
        } catch (e: Exception) {
            emptyList()
        }
        if (imgs.isEmpty()) return products
        var idx = 0
        return products.map { p ->
            if (p.image.isNullOrEmpty() && idx < imgs.size) p.copy(image = imgs[idx++]) else p
        }
    }

    /** Cari gambar produk via Bing Images. */
    private fun searchImages(keyword: String, count: Int, market: Market): List<String> {
        val q = URLEncoder.encode("$keyword ${market.label}", "UTF-8")
        val html = try {
            httpGet("https://www.bing.com/images/search?q=$q&form=HDRSC2&first=1")
        } catch (e: Exception) {
            null
        } ?: return emptyList()
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

    /** Produk asli dari Bing Images: purl (halaman produk) + murl (gambar). */
    private fun searchProducts(keyword: String, limit: Int, market: Market): List<MarketProduct> {
        val q = URLEncoder.encode("$keyword ${market.label}", "UTF-8")
        val html = try {
            httpGet("https://www.bing.com/images/search?q=$q&form=HDRSC2")
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val blocks = Regex("<a[^>]*class=\"iusc\"[^>]*\\sm=\"([^\"]+)\"").findAll(html).toList()
        val rxP = Regex("&quot;purl&quot;:&quot;(.*?)&quot;")
        val rxM = Regex("&quot;murl&quot;:&quot;(.*?)&quot;")
        val out = mutableListOf<MarketProduct>()
        val seen = mutableSetOf<String>()
        for (b in blocks) {
            val attr = b.groupValues[1]
            val purl = rxP.find(attr)?.groupValues?.get(1)?.replace("&amp;", "&")
            val murl = rxM.find(attr)?.groupValues?.get(1)?.replace("&amp;", "&")
            if (purl == null || !market.owns(purl)) continue
            if (!market.isProductUrl(purl)) continue
            val key = purl.substringBefore('?')
            if (!seen.add(key)) continue
            val title = slugToTitle(purl, market)
            if (title.length < 6 || title.matches(Regex("\\d+"))) continue
            out.add(MarketProduct(title = title, url = purl, image = murl, market = market))
            if (out.size >= limit) break
        }
        android.util.Log.i(TAG, "produkGambar=${out.size} blok=${blocks.size} market=${market.label}")
        return out
    }

    /** Ubah slug URL produk menjadi judul yang enak dibaca. */
    private fun slugToTitle(url: String, market: Market): String {
        var slug = when (market) {
            Market.TOKOPEDIA -> {
                val segs = url.substringAfter("tokopedia.com/").substringBefore('?').trim('/')
                    .split('/').filter { it.isNotBlank() }
                // Format: <toko>/<slug> atau pdp/<slug>/<id> -> ambil segmen slug-nya.
                segs.firstOrNull { it.length > 12 && !it.all(Char::isDigit) }
                    ?: segs.lastOrNull().orEmpty()
            }
            Market.BLIBLI -> url.substringAfter("blibli.com/p/").substringBefore("/ps--").substringBefore('?')
            Market.LAZADA -> url.substringAfter("/products/").substringBefore("-i").substringBefore('?')
            else -> url.substringAfter("shopee.co.id/").substringBefore('?')
        }
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

    private fun fallbackSuggestions(keyword: String, limit: Int, market: Market): List<MarketProduct> {
        val kw = keyword.trim()
        val suffixes = listOf("", " murah", " original", " terbaru", " terlaris")
        return suffixes.take(limit).map { sfx ->
            val label = (kw + sfx).trim()
            val encoded = URLEncoder.encode(label, "UTF-8")
            val url = when (market) {
                Market.TOKOPEDIA -> "https://www.tokopedia.com/search?q=$encoded"
                Market.BLIBLI -> "https://www.blibli.com/cari/${encoded.replace("+", "-")}"
                Market.LAZADA -> "https://www.lazada.co.id/catalog/?q=$encoded"
                else -> "https://shopee.co.id/search?keyword=$encoded"
            }
            MarketProduct(
                title = "Cari \"$label\" di ${market.label}",
                url = url,
                market = market
            )
        }
    }

    private fun searchWeb(keyword: String, limit: Int, market: Market): List<MarketProduct> {
        val domain = market.domains.first()
        val q = URLEncoder.encode("site:$domain $keyword", "UTF-8")
        var lite: String? = null
        try {
            lite = httpGet("https://lite.duckduckgo.com/lite/?q=$q&kl=id-id")
            android.util.Log.i(TAG, "lite code-ok len=${lite?.length ?: -1}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "lite failed: ${e.message}")
        }
        var out = if (!lite.isNullOrEmpty()) extractResults(lite, limit, market) else emptyList()
        if (out.isEmpty()) {
            try {
                val bing = httpGet("https://www.bing.com/search?q=$q&setlang=id&count=30")
                android.util.Log.i(TAG, "bing len=${bing?.length ?: -1}")
                if (!bing.isNullOrEmpty()) out = extractResults(bing, limit, market)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "bing failed: ${e.message}")
            }
        }
        return out
    }

    private fun extractResults(html: String, limit: Int, market: Market): List<MarketProduct> {
        val norm = html.replace(Regex("""class\s*=\s*'([^']*)'"""), """class="$1"""")
        val best = LinkedHashMap<String, String>()
        val clean = Regex("<[^>]+>")
        val anchors = Regex("""<a\s[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL))
        for (m in anchors.findAll(norm)) {
            val url = extractUrl(m.groupValues[1]) ?: continue
            if (!market.owns(url)) continue
            if (!market.isProductUrl(url)) continue
            val title = m.groupValues[2]
                .replace(clean, " ")
                .replace("&amp;", "&")
                .replace("&#x27;", "'")
                .replace("&quot;", "\"")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (title.length < 6 || title.matches(Regex("\\d+"))) continue
            val key = url.substringBefore('?')
            val prev = best[key]
            if (prev == null) best[key] = title else if (title.length > prev.length) best[key] = title
            if (best.size >= limit * 4) break
        }
        val out = best.entries.take(limit).map { MarketProduct(it.value, it.key, market = market) }
        android.util.Log.i(TAG, "hasil=${out.size} contoh=${out.firstOrNull()?.title}")
        return out
    }

    // ------------------------------------------------------- info per produk

    /**
     * Baca halaman produk sekali jalan (HTTP biasa) untuk mendapatkan
     * harga, rating, jumlah terjual, dan status stok.
     *
     * - Tokopedia: semua datanya ada di JSON-LD halaman.
     * - Lazada: harga + stok ada; rating/terjual tidak dikirim server.
     * - Blibli: halaman dikunci Cloudflare -> status tidak diketahui.
     * - Shopee: harga/rating/terjual hanya bisa dibaca setelah halaman dirender
     *   (lihat PapanActivity), di sini hanya status stoknya.
     */
    fun productInfo(market: Market, url: String): ProductInfo {
        val resp = try {
            httpGetWithCode(url)
        } catch (e: Exception) {
            null
        } ?: return ProductInfo(status = STATUS_UNKNOWN)

        // 404 = produk sudah dihapus.
        if (resp.first == 404 || resp.first == 410) return ProductInfo(status = STATUS_DEAD)
        if (resp.first !in 200..299) return ProductInfo(status = STATUS_UNKNOWN)

        val html = resp.second
        // Halaman tantangan Cloudflare (Blibli) -> jangan dianggap produk mati.
        if (html.contains("_cf_chl_opt") || html.contains("cf-chl")) {
            return ProductInfo(status = STATUS_UNKNOWN)
        }

        return when (market) {
            Market.TOKOPEDIA -> {
                val rating = Regex("\"ratingValue\"\\s*:\\s*([\\d.]+)")
                    .find(html)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                val priceNum = Regex("\"price\"\\s*:\\s*(\\d{3,})")
                    .find(html)?.groupValues?.get(1)?.toLongOrNull()
                val soldText = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"titleEn\"\\s*:\\s*\"[^\"]*\"\\s*,\\s*\"subtitle\"\\s*:\\s*\"Terjual\"")
                    .find(html)?.groupValues?.get(1)
                    ?: Regex("<span class=\"title\">([^<]+)</span><span>\\s*</span><span class=\"subtitle\">Terjual</span>")
                        .find(html)?.groupValues?.get(1)
                ProductInfo(
                    price = priceNum?.let { formatRupiah(it) } ?: "",
                    rating = rating,
                    sold = parseCount(soldText),
                    status = statusFromHtml(html)
                )
            }

            Market.LAZADA -> {
                val price = Regex("\"pdt_price\"\\s*:\\s*\"([^\"]+)\"")
                    .find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trim() ?: ""
                ProductInfo(price = price, status = statusFromHtml(html))
            }

            Market.SHOPEE -> {
                // Shopee tidak punya JSON-LD Product -> pakai penanda khas Shopee.
                val status = when {
                    html.length < 30_000 -> STATUS_UNKNOWN // halaman tidak lengkap/terblokir
                    !Regex("\"itemid\"\\s*:\\s*\\d+").containsMatchIn(html) -> STATUS_DEAD
                    html.contains("\"stock_display\":\"STOK HABIS\"") -> STATUS_SOLD_OUT
                    else -> STATUS_ALIVE
                }
                ProductInfo(status = status)
            }

            Market.BLIBLI -> ProductInfo(status = STATUS_UNKNOWN)
        }
    }

    private fun statusFromHtml(html: String): Int = when {
        html.length < 30_000 -> STATUS_UNKNOWN // halaman tidak lengkap/terblokir
        !Regex("\"@type\"\\s*:\\s*\"Product").containsMatchIn(html) -> STATUS_DEAD
        html.contains("https://schema.org/OutOfStock") -> STATUS_SOLD_OUT
        html.contains("\"stock_display\":\"STOK HABIS\"") -> STATUS_SOLD_OUT
        else -> STATUS_ALIVE
    }

    /** "3 rb+" -> 3000, "1,2 rb" -> 1200, "500" -> 500. */
    private fun parseCount(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val t = text.lowercase().replace("+", " ").trim()
        val num = Regex("([\\d.,]+)").find(t)?.groupValues?.get(1) ?: return 0
        val value = num.replace(".", "").replace(",", ".").toDoubleOrNull() ?: return 0
        return when {
            t.contains("rb") || t.contains("ribu") -> (value * 1000).toInt()
            t.contains("jt") || t.contains("juta") -> (value * 1000000).toInt()
            else -> value.toInt()
        }
    }

    fun formatRupiah(value: Long): String {
        val s = value.toString()
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append('.')
            sb.append(s[i])
        }
        return "Rp$sb"
    }

    /** 3000 -> "3rb", 1500000 -> "1,5jt". */
    fun formatCount(n: Int): String {
        val s = when {
            n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fjt", n / 1_000_000.0)
            n >= 1000 -> String.format(java.util.Locale.US, "%.1frb", n / 1000.0)
            else -> n.toString()
        }
        return s.replace('.', ',').replace(Regex(",0(?=(rb|jt)$)"), "")
    }

    // --------------------------------------------------------------- utilitas

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

    /** Buka produk di aplikasi marketplace-nya, fallback ke browser. */
    fun openInMarket(context: Context, url: String) {
        val target = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        for (pkg in marketOf(url)?.apps ?: emptyList()) {
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

    /** Seperti [httpGet] tapi juga mengembalikan kode status HTTP (untuk membedakan 404). */
    private fun httpGetWithCode(url: String): Pair<Int, String>? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "id-ID,id;q=0.9")
        try {
            val code = conn.responseCode
            if (code !in 200..299) return code to ""
            val body = try {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } catch (e: Exception) {
                ""
            }
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "id-ID,id;q=0.9")
        try {
            if (conn.responseCode != 200) return null
            return BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }


    private fun extractUrl(href: String): String? {
        if (href.contains("uddg=")) {
            val enc = href.substringAfter("uddg=").substringBefore("&")
            return try {
                URLDecoder.decode(enc, "UTF-8")
            } catch (e: Exception) {
                enc
            }
        }
        return if (href.startsWith("http")) href else null
    }
}
