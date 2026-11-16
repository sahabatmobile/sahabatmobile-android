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
 *  1. Cek status produk via HTTP biasa ([ShopeeSearch.productStatus]) ->
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
        val cards = PapanStore.loadVisible(this)

        tvSubtitle.text = if (cards.isEmpty()) {
            getString(R.string.papan_subtitle)
        } else {
            getString(R.string.papan_count, cards.size)
        }
        btnClear.visibility = if (cards.isEmpty()) View.GONE else View.VISIBLE

        if (cards.isEmpty()) {
            llBoard.addView(emptyView())
            return
        }
        cards.forEach { addCard(it) }
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

        val isProduct = Regex("-i\\.\\d+\\.\\d+").containsMatchIn(card.url)
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
        open.text = getString(R.string.papan_open_shopee)
        open.textSize = 12f
        open.setTypeface(null, android.graphics.Typeface.BOLD)
        open.setTextColor(getColor(R.color.primary))
        open.setPadding(0, dp(8), dp(18), dp(8))
        open.isClickable = true
        open.setOnClickListener { ShopeeSearch.openInShopee(this, card.url) }
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
            val bmp = withContext(Dispatchers.IO) { ShopeeSearch.loadImage(url) }
            images[url] = bmp
            if (bmp != null) iv.setImageBitmap(bmp)
        }
    }

    /**
     * Cek status produk via HTTP biasa (tanpa anti-bot).
     * Produk yang halamannya kosong/dihapus atau stok habis ditandai [PapanStore.markEmpty]
     * sehingga tidak ikut tampil.
     */
    private fun startStatusCheck() {
        if (checkRunning) return
        val targets = PapanStore.loadVisible(this).filter {
            !it.checked && Regex("-i\\.\\d+\\.\\d+").containsMatchIn(it.url)
        }
        if (targets.isEmpty()) {
            startPrices(pendingUrls())
            return
        }
        checkRunning = true
        scope.launch {
            for (c in targets) {
                val st = withContext(Dispatchers.IO) {
                    try { Thread.sleep(350) } catch (e: Exception) {}
                    ShopeeSearch.productStatus(c.url)
                }
                when (st) {
                    ShopeeSearch.STATUS_DEAD, ShopeeSearch.STATUS_SOLD_OUT -> {
                        android.util.Log.i(TAG, "buang ($st): ${c.url.takeLast(50)}")
                        PapanStore.markEmpty(this@PapanActivity, c.url)
                        removeCardView(c.url)
                    }
                    ShopeeSearch.STATUS_ALIVE ->
                        PapanStore.markChecked(this@PapanActivity, c.url)
                }
            }
            checkRunning = false
            pendingSet = pendingUrls().toSet()
            startPrices(pendingUrls())
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

    /** Kartu aktif yang harganya belum diketahui. */
    private fun pendingUrls(): List<String> {
        if (System.currentTimeMillis() < pauseUntil()) return emptyList()
        val now = System.currentTimeMillis()
        return PapanStore.loadVisible(this)
            .filter {
                val isProduct = Regex("-i\\.\\d+\\.\\d+").containsMatchIn(it.url)
                if (!isProduct || it.empty || !it.checked) return@filter false
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
            val res = parseJsPrice(raw)
            val price = res.first
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
                    PapanStore.setPrice(this, url, price)
                    showPrice(url, price)
                    priceUrl = null
                    scheduleNext(PAGE_GAP_MS)
                }
                priceTries < MAX_TRIES -> {
                    handler.postDelayed(pollPriceRunnable, 1800)
                }
                else -> {
                    android.util.Log.w(TAG, "harga tak ketemu: ${url.takeLast(50)} | ${res.second}")
                    val blocked = res.second.isEmpty() || res.second.contains("Masuk", true)
                    if (blocked) {
                        // Halaman kosong / minta login -> hentikan sisa antrean, coba lagi lain waktu.
                        priceQueue.clear()
                        pausePriceFetch()
                    }
                    if (!pricePageError) PapanStore.setPrice(this, url, "-")
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

    /** Hasil evaluateJavascript: JSON {"p": harga, "t": cuplikan teks halaman}. */
    private fun parseJsPrice(raw: String?): Pair<String, String> {
        if (raw.isNullOrBlank() || raw == "null") return "" to ""
        return try {
            val o = org.json.JSONObject(raw)
            val price = o.optString("p").replace(Regex("\\s+"), "").take(24)
            val text = o.optString("t").replace(Regex("\\s+"), " ").take(200)
            price to text
        } catch (e: Exception) {
            "" to ""
        }
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
        const val JS_PRICE = """(function(){try{var out='',flat='';var b=document.body?document.body.cloneNode(true):null;if(b){var s=b.querySelectorAll('script,style,noscript');for(var i=0;i<s.length;i++){s[i].parentNode.removeChild(s[i]);}var w=document.createTreeWalker(b,NodeFilter.SHOW_TEXT,null),n;while(n=w.nextNode()){var t=(n.nodeValue||'').replace(/\s+/g,'');if(/^Rp\d{3,}$/.test(t)){out=t;break;}}flat=(b.textContent||'').replace(/\s+/g,' ');if(!out){var m=flat.match(/Rp(\d{4,})/);if(m){out='Rp'+m[1];}}if(out){var v=parseInt(out.replace('Rp',''),10);if(!(v>=1000)){out='';}}}return JSON.stringify({p:out,t:flat.substring(0,200)});}catch(err){return JSON.stringify({p:'',t:''});}})()"""
    }
}
