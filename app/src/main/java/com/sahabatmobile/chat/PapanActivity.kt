package com.sahabatmobile.chat

import android.graphics.Bitmap
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
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
 */
class PapanActivity : AppCompatActivity() {

    private lateinit var llBoard: LinearLayout
    private lateinit var tvSubtitle: TextView
    private lateinit var btnClear: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val images = mutableMapOf<String, Bitmap?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_papan)

        llBoard = findViewById(R.id.llBoard)
        tvSubtitle = findViewById(R.id.tvBoardSubtitle)
        btnClear = findViewById(R.id.btnBoardClear)

        findViewById<ImageView>(R.id.btnPapanBack).setOnClickListener { finish() }
        btnClear.setOnClickListener { confirmClear() }

        loadBoard()
    }

    override fun onDestroy() {
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
}
