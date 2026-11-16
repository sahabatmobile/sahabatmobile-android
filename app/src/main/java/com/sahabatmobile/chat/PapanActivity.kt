package com.sahabatmobile.chat

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Papan Hasil AI — daftar kartu yang di-pin pengguna dari chat.
 * Isi disimpan lewat [PapanStore]; layar ini hanya menampilkan, membuka, dan menghapus kartu.
 *
 * Dua proses latar:
 *  1. Cek status + data produk via HTTP biasa ([MarketSearch.productInfo]) ->
 *     produk yang halamannya kosong / sudah dihapus / stok habis disembunyikan.
 *  2. Ambil harga. Harga tidak ada di HTML Shopee (dirender via JS), jadi halaman produk
 *     dimuat di WebView tersembunyi lalu teks harganya dibaca. Kalau Shopee meminta
 *     verifikasi/login, pengambilan dijeda dan harga tampil belakangan.
 */
class PapanActivity : AppCompatActivity() {

    private lateinit var llBoard: LinearLayout
    private lateinit var tvSubtitle: TextView
    private lateinit var btnClear: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val images = mutableMapOf<String, Bitmap?>()

    private var checkRunning = false
    private var showAll = false

    private var priceWeb: WebView? = null
    private var priceUrl: String? = null
    private var priceTries = 0
    private var pricePageError = false
    private var httpStatus = 0
    private var priceQueue = ArrayDeque<String>()
    private var pendingSet: Set<String> = emptySet()
    private val handler = Handler(Looper.getMainLooper())
    private val pollPriceRunnable = Runnable { pollPrice() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_papan)

        llBoard = findViewById(R.id.llBoard)
        tvSubtitle = findViewById(R.id.tvBoardSubtitle)
        btnClear = findViewById(R.id.btnBoardClear)

        findViewById<ImageView>(R.id.btnPapanBack).setOnClickListener { finish() }
        btnClear.setOnClickListener { confirmClear() }

        loadBoard()
        preparePriceWeb()
        // Beranda Shopee dibuka lebih dulu supaya WebView punya cookie sesi.
        priceWeb?.loadUrl(HOME_URL)
        startStatusCheck()
    }

    override fun onResume() {
        super.onResume()
        // Kartu baru mungkin saja baru dipin dari chat -> muat ulang & cek yang belum dicek.
        loadBoard()
        startStatusCheck()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        priceWeb?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        priceWeb = null
        scope.cancel()
        super.onDestroy()
    }

    private fun loadBoard() {
        llBoard.removeAllViews()
        pendingSet = pendingUrls().toSet()
        val all = PapanStore.loadVisible(this)
        val kept = applyFilter(all)

        tvSubtitle.text = if (all.isEmpty()) {
            getString(R.string.papan_subtitle)
        } else {
            getString(R.string.papan_count, all.size)
        }
        btnClear.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE

        val hint = findViewById<TextView>(R.id.tvVerifyHint)
        if (all.isNotEmpty() && System.currentTimeMillis() < pauseUntil()) {
            hint.visibility = View.VISIBLE
            hint.setOnClickListener { openVerify() }
        } else {
            hint.visibility = View.GONE
        }

        if (kept.isEmpty()) {
            llBoard.addView(emptyView())
            return
        }
        kept.forEach { addCard(it) }
        val hidden = all.size - kept.size
        if (hidden > 0) llBoard.addView(hiddenView(hidden))
    }

    /**
     * Filter kualitas produk — nilai rating diambil dari halaman produk Shopee.
     * Ambil tier terbaik yang tersedia: 4.9 -> 4.8 -> 4.7 (minimum).
     * Produk dengan penjualan terbanyak tetap tampil walau ratingnya di bawah tier,
     * dan produk yang ratingnya belum diketahui tidak dibuang.
     */
    private fun applyFilter(cards: List<PapanStore.Card>): List<PapanStore.Card> {
        if (showAll || cards.isEmpty()) return sortBySales(cards)
        val tier = when {
            cards.any { it.rating >= 4.9 } -> 4.9
            cards.any { it.rating >= 4.8 } -> 4.8
            cards.any { it.rating >= 4.7 } -> 4.7
            else -> 0.0
        }
        if (tier <= 0.0) return sortBySales(cards)
        val topSeller = cards.filter { it.sold > 0 }.maxByOrNull { it.sold }?.url
        return sortBySales(cards.filter {
            it.rating <= 0.0 || it.rating >= tier || it.url == topSeller
        })
    }

    /** Urut: penjualan terbanyak dulu, lalu rating tertinggi. */
    private fun sortBySales(cards: List<PapanStore.Card>): List<PapanStore.Card> =
        cards.sortedWith(
            compareByDescending<PapanStore.Card> { it.sold }.thenByDescending { it.rating }
        )

    private fun hiddenView(count: Int): TextView {
        val tv = TextView(this)
        tv.text = getString(R.string.papan_show_all, count)
        tv.textSize = 12f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(getColor(R.color.textOnBrandMuted))
        tv.setPadding(dp(12), dp(10), dp(12), dp(18))
        tv.isClickable = true
        tv.setOnClickListener {
            showAll = true
            loadBoard()
        }
        return tv
    }

    private fun emptyView(): TextView {
        val tv = TextView(this)
        tv.text = getString(R.string.papan_empty)
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(getColor(R.color.textOnBrandMuted))
        tv.setPadding(dp(24), dp(56), dp(24), dp(24))
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return tv
    }

    private fun addCard(card: PapanStore.Card) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = getDrawable(R.drawable.bg_card)
        box.setPadding(dp(12), dp(12), dp(12), dp(6))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        box.layoutParams = lp

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val thumb = ImageView(this)
        thumb.layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.clipToOutline = true
        thumb.background = getDrawable(R.drawable.bg_preview)
        thumb.contentDescription = card.title
        loadThumb(thumb, card.image)
        top.addView(thumb)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        val colParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        colParams.marginStart = dp(12)
        column.layoutParams = colParams

        val title = TextView(this)
        title.text = card.title
        title.textSize = 13.5f
        title.setTextColor(getColor(R.color.textPrimary))
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        column.addView(title)

        val market = MarketSearch.marketOf(card.url)
        val isProduct = market?.isProductUrl(card.url) ?: false
        val price = TextView(this)
        price.tag = "price:${card.url}"
        price.textSize = 14f
        price.setTypeface(null, android.graphics.Typeface.BOLD)
        val hasPrice = card.price.isNotEmpty() && card.price != "-"
        when {
            hasPrice -> price.text = card.price
            isProduct && pendingSet.contains(card.url) ->
                price.text = getString(R.string.papan_price_loading)
            else -> price.visibility = View.GONE
        }
        price.setTextColor(getColor(if (hasPrice) R.color.primary else R.color.textSecondary))
        price.setPadding(0, dp(2), 0, 0)
        column.addView(price)

        val meta = LinearLayout(this)
        meta.orientation = LinearLayout.HORIZONTAL
        meta.gravity = Gravity.CENTER_VERTICAL
        val metaParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        metaParams.topMargin = dp(3)
        meta.layoutParams = metaParams

        if (market != null) {
            val badge = TextView(this)
            badge.text = market.label
            badge.textSize = 10f
            badge.setTypeface(null, android.graphics.Typeface.BOLD)
            badge.setTextColor(getColor(R.color.textSecondary))
            badge.setPadding(0, 0, dp(8), 0)
            meta.addView(badge)
        }

        if (card.rating > 0.0) {
            val rating = TextView(this)
            rating.text = getString(
                R.string.papan_rating,
                String.format(Locale.getDefault(), "%.1f", card.rating)
            )
            rating.textSize = 11f
            rating.setTypeface(null, android.graphics.Typeface.BOLD)
            rating.setTextColor(getColor(R.color.gold))
            rating.setPadding(0, 0, dp(8), 0)
            meta.addView(rating)
        }
        if (card.sold > 0) {
            val sold = TextView(this)
            sold.text = getString(R.string.papan_sold, formatSold(card.sold))
            sold.textSize = 11f
            sold.setTextColor(getColor(R.color.textSecondary))
            sold.setPadding(0, 0, dp(8), 0)
            meta.addView(sold)
        }
        if (card.keyword.isNotEmpty()) {
            val keyword = TextView(this)
            keyword.text = getString(R.string.papan_keyword, card.keyword)
            keyword.textSize = 10.5f
            keyword.setTextColor(getColor(R.color.textSecondary))
            keyword.maxLines = 1
            keyword.ellipsize = TextUtils.TruncateAt.END
            meta.addView(keyword)
        }
        if (meta.childCount > 0) column.addView(meta)

        top.addView(column)
        box.addView(top)

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        val actParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        actParams.topMargin = dp(6)
        actions.layoutParams = actParams

        val open = TextView(this)
        open.text = getString(R.string.papan_open_market, market?.label ?: "Toko")
        open.textSize = 12f
        open.setTypeface(null, android.graphics.Typeface.BOLD)
        open.setTextColor(getColor(R.color.primary))
        open.setPadding(0, dp(8), dp(18), dp(8))
        open.isClickable = true
        open.setOnClickListener { MarketSearch.openInMarket(this, card.url) }
        actions.addView(open)

        val del = TextView(this)
        del.text = getString(R.string.papan_remove)
        del.textSize = 12f
        del.setTypeface(null, android.graphics.Typeface.BOLD)
        del.setTextColor(getColor(R.color.errorText))
        del.setPadding(0, dp(8), 0, dp(8))
        del.isClickable = true
        del.setOnClickListener {
            PapanStore.remove(this, card.url)
            Toast.makeText(this, R.string.papan_removed, Toast.LENGTH_SHORT).show()
            loadBoard()
        }
        actions.addView(del)

        box.addView(actions)
        llBoard.addView(box)
    }

    private fun loadThumb(iv: ImageView, url: String?) {
        if (url.isNullOrEmpty()) return
        if (images.containsKey(url)) {
            images[url]?.let { iv.setImageBitmap(it) }
            return
        }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { MarketSearch.loadImage(url) }
            images[url] = bmp
            if (bmp != null) iv.setImageBitmap(bmp)
        }
    }

    /**
     * Cek status + data produk via HTTP biasa (tanpa anti-bot).
     * Untuk Tokopedia & Lazada harga/rating/terjual sudah ikut terbaca di sini.
     * Produk yang halamannya kosong / sudah dihapus / stok habis ditandai
     * [PapanStore.markEmpty] sehingga tidak ikut tampil.
     */
    private fun startStatusCheck() {
        if (checkRunning) return
        val targets = PapanStore.loadVisible(this).filter { !it.checked }
        if (targets.isEmpty()) {
            handler.postDelayed({ startPrices(pendingUrls()) }, 2500)
            return
        }
        checkRunning = true
        scope.launch {
            for (c in targets) {
                val market = MarketSearch.marketOf(c.url)
                val info = withContext(Dispatchers.IO) {
                    try {
                        Thread.sleep(350)
                    } catch (e: Exception) {
                    }
                    if (market == null) ProductInfo(status = MarketSearch.STATUS_UNKNOWN)
                    else MarketSearch.productInfo(market, c.url)
                }
                when (info.status) {
                    MarketSearch.STATUS_DEAD, MarketSearch.STATUS_SOLD_OUT -> {
                        android.util.Log.i(TAG, "buang (${info.status}): ${c.url.takeLast(50)}")
                        PapanStore.markEmpty(this@PapanActivity, c.url)
                        removeCardView(c.url)
                    }

                    MarketSearch.STATUS_ALIVE -> {
                        if (info.price.isNotEmpty() || info.rating > 0 || info.sold > 0) {
                            PapanStore.setInfo(
                                this@PapanActivity, c.url,
                                info.price, info.rating, info.sold
                            )
                        } else {
                            // Belum ada data harga/rating (mis. Shopee) -> dicoba lewat WebView.
                            PapanStore.markChecked(this@PapanActivity, c.url)
                        }
                    }
                }
            }
            checkRunning = false
            // Muat ulang agar harga/rating/terjual yang baru terbaca langsung tampil.
            loadBoard()
            handler.postDelayed({ startPrices(pendingUrls()) }, 2500)
        }
    }

    // ------------------------------------------------------------------ harga

    private fun preparePriceWeb() {
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
        wv.settings.userAgentString = PRICE_UA
        wv.settings.javaScriptCanOpenWindowsAutomatically = false
        wv.settings.setSupportMultipleWindows(false)
        wv.alpha = 0f
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pricePageError = false
                httpStatus = 0
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) pricePageError = true
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) httpStatus = error?.statusCode ?: 0
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url?.contains("/verify/traffic") == true) {
                    // Shopee meminta verifikasi -> jangan dipaksa, lanjut lain waktu.
                    android.util.Log.w(TAG, "verifikasi Shopee, pengambilan harga dijeda")
                    priceUrl?.let { hidePrice(it) }
                    priceUrl = null
                    priceQueue.clear()
                    pausePriceFetch()
                    return
                }
                try { android.webkit.CookieManager.getInstance().flush() } catch (e: Exception) {}
                handler.removeCallbacks(pollPriceRunnable)
                handler.postDelayed(pollPriceRunnable, 1200)
            }
        }
        findViewById<LinearLayout>(R.id.rootPapan).addView(
            wv,
            LinearLayout.LayoutParams(dp(1), dp(1))
        )
        priceWeb = wv
    }

    /** Kartu yang harganya belum diketahui (diambil lewat WebView). */
    private fun pendingUrls(): List<String> {
        if (System.currentTimeMillis() < pauseUntil()) return emptyList()
        val now = System.currentTimeMillis()
        return PapanStore.loadVisible(this)
            .filter {
                val market = MarketSearch.marketOf(it.url) ?: return@filter false
                if (!market.isProductUrl(it.url) || it.empty || !it.checked) return@filter false
                it.price.isEmpty() || (it.price == "-" && now - it.priceTs > RETRY_PRICE_MS)
            }
            .take(MAX_FETCH)
            .map { it.url }
    }

    private fun startPrices(urls: List<String>) {
        if (priceWeb == null || priceUrl != null) return
        priceQueue.clear()
        priceQueue.addAll(urls)
        if (priceQueue.isNotEmpty()) nextPrice()
    }

    /** Jeda sementara setelah Shopee minta verifikasi/login. */
    private fun pauseUntil(): Long =
        getSharedPreferences("sm_prefs", MODE_PRIVATE).getLong("papan_price_pause", 0L)

    private fun pausePriceFetch() {
        getSharedPreferences("sm_prefs", MODE_PRIVATE).edit()
            .putLong("papan_price_pause", System.currentTimeMillis() + PAUSE_MS).apply()
    }

    private fun nextPrice() {
        val url = priceQueue.removeFirstOrNull()
        if (url == null) {
            priceUrl = null
            return
        }
        val wv = priceWeb ?: return
        priceUrl = url
        priceTries = 0
        pricePageError = false
        httpStatus = 0
        handler.removeCallbacks(pollPriceRunnable)
        wv.stopLoading()
        wv.loadUrl(url)
    }

    private fun pollPrice() {
        val url = priceUrl ?: return
        val wv = priceWeb ?: return
        priceTries++
        wv.evaluateJavascript(JS_PRICE) { raw ->
            if (priceUrl != url) return@evaluateJavascript
            val info = parseJsInfo(raw)
            val price = info.price
            when {
                // Diblokir Shopee (403/429) -> jeda, bukan berarti produk kosong.
                httpStatus in 400..599 -> {
                    android.util.Log.w(TAG, "diblokir ($httpStatus), jeda")
                    hidePrice(url)
                    priceUrl = null
                    priceQueue.clear()
                    pausePriceFetch()
                }
                price.isNotEmpty() -> {
                    PapanStore.setInfo(this, url, price, info.rating, info.sold)
                    showPrice(url, price)
                    priceUrl = null
                    scheduleNext(PAGE_GAP_MS)
                }
                priceTries < MAX_TRIES -> {
                    handler.postDelayed(pollPriceRunnable, 1800)
                }
                else -> {
                    android.util.Log.w(TAG, "harga tak ketemu: ${url.takeLast(50)} | ${info.text}")
                    val blocked = info.text.isEmpty() || info.text.contains("Masuk", true)
                    if (blocked) {
                        // Halaman kosong / minta login -> hentikan sisa antrean, coba lagi lain waktu.
                        priceQueue.clear()
                        pausePriceFetch()
                    }
                    if (!pricePageError) PapanStore.setInfo(this, url, "-", info.rating, info.sold)
                    hidePrice(url)
                    priceUrl = null
                    if (!blocked) scheduleNext(PAGE_GAP_MS)
                }
            }
        }
    }

    private fun scheduleNext(delayMs: Long) {
        handler.postDelayed({ nextPrice() }, delayMs)
    }

    private fun showPrice(url: String, price: String) {
        val tv = llBoard.findViewWithTag<TextView>("price:$url") ?: return
        tv.text = price
        tv.setTextColor(getColor(R.color.primary))
        tv.visibility = View.VISIBLE
    }

    private fun hidePrice(url: String) {
        val tv = llBoard.findViewWithTag<TextView>("price:$url") ?: return
        tv.text = ""
        tv.visibility = View.GONE
    }

    /** Hilangkan kartu dari layar (produk kosong / stok habis). */
    private fun removeCardView(url: String) {
        val tv = llBoard.findViewWithTag<TextView>("price:$url") ?: return
        val card = tv.parent?.parent?.parent as? View ?: return
        llBoard.removeView(card)
        val visible = PapanStore.loadVisible(this).size
        tvSubtitle.text = getString(R.string.papan_count, visible)
        btnClear.visibility = if (visible == 0) View.GONE else View.VISIBLE
    }

    /** Hasil evaluateJavascript: harga, rating, jumlah terjual, dan cuplikan teks halaman. */
    private class PageInfo(val price: String, val rating: Double, val sold: Int, val text: String)

    private fun parseJsInfo(raw: String?): PageInfo {
        if (raw.isNullOrBlank() || raw == "null") return PageInfo("", 0.0, 0, "")
        return try {
            val o = org.json.JSONObject(raw)
            PageInfo(
                price = o.optString("p").replace(Regex("\\s+"), "").take(24),
                rating = o.optString("r").toDoubleOrNull() ?: 0.0,
                sold = o.optString("s").toIntOrNull() ?: 0,
                text = o.optString("t").replace(Regex("\\s+"), " ").take(200)
            )
        } catch (e: Exception) {
            PageInfo("", 0.0, 0, "")
        }
    }

    private fun formatSold(n: Int): String {
        val s = when {
            n >= 1_000_000 -> String.format(Locale.US, "%.1fjt", n / 1_000_000.0)
            n >= 1000 -> String.format(Locale.US, "%.1frb", n / 1000.0)
            else -> n.toString()
        }
        return s.replace('.', ',').replace(Regex(",0(?=(rb|jt)$)"), "")
    }

    /**
     * Buka Shopee di WebView terlihat supaya pengguna bisa lewat verifikasi / login.
     * Cookie sesi dipakai bersama dengan WebView pengambil harga & rating.
     */
    private fun openVerify() {
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = PRICE_UA
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(16), dp(8), dp(16), 0)

        val hint = TextView(this)
        hint.text = getString(R.string.papan_verify_step)
        hint.textSize = 11.5f
        hint.setTextColor(getColor(R.color.textSecondary))
        wrap.addView(hint)

        val login = TextView(this)
        login.text = getString(R.string.papan_verify_login)
        login.textSize = 12f
        login.setTypeface(null, android.graphics.Typeface.BOLD)
        login.setTextColor(getColor(R.color.primary))
        login.setPadding(0, dp(8), 0, 0)
        login.isClickable = true
        login.setOnClickListener { wv.loadUrl(LOGIN_URL) }
        wrap.addView(login)

        val box = android.widget.FrameLayout(this)
        box.addView(
            wv,
            android.widget.FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 3 / 4)
            )
        )
        val boxParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        boxParams.topMargin = dp(8)
        wrap.addView(box, boxParams)

        // Langsung ke produk pertama yang harganya belum ketemu (sekalian verifikasi).
        wv.loadUrl(pendingUrls().firstOrNull() ?: HOME_URL)
        AlertDialog.Builder(this)
            .setTitle(R.string.papan_verify_title)
            .setView(wrap)
            .setPositiveButton(R.string.papan_verify_done) { _, _ -> }
            .setOnDismissListener {
                wv.stopLoading()
                wv.destroy()
                // Sesi baru -> pengambilan harga & rating dicoba lagi.
                getSharedPreferences("sm_prefs", MODE_PRIVATE).edit()
                    .remove("papan_price_pause").apply()
                loadBoard()
                startPrices(pendingUrls())
            }
            .show()
    }

    private fun confirmClear() {
        if (PapanStore.count(this) == 0) return
        AlertDialog.Builder(this)
            .setTitle(R.string.papan_title)
            .setMessage(R.string.papan_clear_confirm)
            .setPositiveButton(R.string.papan_clear) { _, _ ->
                PapanStore.clear(this)
                Toast.makeText(this, R.string.papan_cleared, Toast.LENGTH_SHORT).show()
                loadBoard()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "SM_PAPAN"
        const val PRICE_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        const val MAX_FETCH = 5
        const val MAX_TRIES = 6
        const val PAGE_GAP_MS = 8000L
        const val PAUSE_MS = 30 * 60 * 1000L
        const val RETRY_PRICE_MS = 6 * 60 * 60 * 1000L
        const val HOME_URL = "https://shopee.co.id/"
        const val LOGIN_URL = "https://shopee.co.id/buyer/login"
        const val JS_PRICE = """(function(){try{var out='',flat='',r='',s='';var b=document.body?document.body.cloneNode(true):null;if(b){var sc=b.querySelectorAll('script,style,noscript');for(var i=0;i<sc.length;i++){sc[i].parentNode.removeChild(sc[i]);}var w=document.createTreeWalker(b,NodeFilter.SHOW_TEXT,null),n;while(n=w.nextNode()){var t=(n.nodeValue||'').replace(/\s+/g,'');if(/^Rp\d{3,}$/.test(t)){out=t;break;}}flat=(b.textContent||'').replace(/\s+/g,' ');if(!out){var m=flat.match(/Rp(\d{4,})/);if(m){out='Rp'+m[1];}}if(out){var v=parseInt(out.replace('Rp',''),10);if(!(v>=1000)){out='';}}var head=flat.substring(0,250);var rm=head.match(/(\d[.,]\d{1,2})\s*\/\s*5/)||head.match(/(\d[.,]\d{1,2})\s*(?:Penilaian|rating|Ulasan)/i);if(rm){var rv=parseFloat(rm[1].replace(',','.'));if(rv>0&&rv<=5){r=String(rv);}}var sm=flat.match(/Terjual\s*([\d.]+(?:[.,]\d+)?)\s*(rb|ribu|jt|juta)?/i)||flat.match(/([\d.]+(?:[.,]\d+)?)\s*(rb|ribu|jt|juta)?\s*Terjual/i);if(sm){var num=parseFloat(sm[1].replace(/\./g,'').replace(',','.'));var mul=1;if(sm[2]){if(/^(rb|ribu)$/i.test(sm[2])){mul=1000;}else if(/^(jt|juta)$/i.test(sm[2])){mul=1000000;}}if(num>0){s=String(Math.round(num*mul));}}}return JSON.stringify({p:out,r:r,s:s,t:flat.substring(0,200)});}catch(err){return JSON.stringify({p:'',r:'',s:'',t:''});}})()"""
    }
}
