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
 * Harga Shopee tidak ada di HTML (dirender via JS), jadi harga diambil dengan memuat
 * halaman produk di WebView tersembunyi lalu membaca teks halaman.
 */
class PapanActivity : AppCompatActivity() {

    private lateinit var llBoard: LinearLayout
    private lateinit var tvSubtitle: TextView
    private lateinit var btnClear: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val images = mutableMapOf<String, Bitmap?>()

    private var priceWeb: WebView? = null
    private var priceUrl: String? = null
    private var priceTries = 0
    private var pricePageError = false
    private val priceQueue = ArrayDeque<String>()
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
        startPrices()
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
        val cards = PapanStore.load(this)

        tvSubtitle.text = if (cards.isEmpty()) {
            getString(R.string.papan_subtitle)
        } else {
            getString(R.string.papan_count, cards.size)
        }
        btnClear.visibility = if (cards.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        if (cards.isEmpty()) {
            llBoard.addView(emptyView())
            return
        }
        cards.forEachIndexed { index, card -> addCard(card, index) }
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

    private fun addCard(card: PapanStore.Card, index: Int = 0) {
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
            card.price.isEmpty() && isProduct && index < MAX_FETCH ->
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
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) pricePageError = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
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

    /** Antrekan kartu yang harganya belum diketahui. */
    private fun startPrices() {
        if (priceWeb == null || priceUrl != null) return
        val now = System.currentTimeMillis()
        val pending = PapanStore.load(this)
            .filter {
                val isProduct = Regex("-i\\.\\d+\\.\\d+").containsMatchIn(it.url)
                if (!isProduct) return@filter false
                when (it.price) {
                    "" -> true
                    "-" -> now - it.priceTs > RETRY_PRICE_MS
                    else -> false
                }
            }
            .take(MAX_FETCH)
        priceQueue.clear()
        priceQueue.addAll(pending.map { it.url })
        if (priceQueue.isNotEmpty()) nextPrice()
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
            if (price.isNotEmpty()) {
                PapanStore.setPrice(this, url, price)
                showPrice(url, price)
                priceUrl = null
                handler.postDelayed({ nextPrice() }, 600)
            } else if (priceTries < MAX_TRIES) {
                handler.postDelayed(pollPriceRunnable, 1800)
            } else {
                android.util.Log.w(TAG, "harga tak ketemu: $url | ${res.second}")
                // Halaman terbuka normal tapi harga tetap tak ketemu -> coba lagi setelah beberapa jam.
                if (!pricePageError) PapanStore.setPrice(this, url, "-")
                hidePrice(url)
                priceUrl = null
                nextPrice()
            }
        }
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
        const val MAX_FETCH = 12
        const val MAX_TRIES = 10
        const val RETRY_PRICE_MS = 6 * 60 * 60 * 1000L
        const val JS_PRICE = """(function(){try{var b=document.body.cloneNode(true);var s=b.querySelectorAll('script,style,noscript');for(var i=0;i<s.length;i++){s[i].parentNode.removeChild(s[i]);}var out='';var w=document.createTreeWalker(b,NodeFilter.SHOW_TEXT,null);var n;while(n=w.nextNode()){var t=(n.nodeValue||'').replace(/\s+/g,'');if(/^Rp\d{3,}$/.test(t)){out=t;break;}}if(!out){var all=(b.textContent||'').replace(/\s+/g,'');var m=all.match(/Rp(\d{4,})/);if(m){out='Rp'+m[1];}}if(out){var v=parseInt(out.replace('Rp',''),10);if(!(v>=1000)){out='';}}return JSON.stringify({p:out,t:(b.textContent||'').substring(0,400)});}catch(e){return JSON.stringify({p:'',t:''});}})()"""
    }
}
