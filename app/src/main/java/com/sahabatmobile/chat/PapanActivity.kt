package com.sahabatmobile.chat

import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PapanActivity : AppCompatActivity() {

    private lateinit var llBoard: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_papan)
        llBoard = findViewById(R.id.llBoard)
        val title = findViewById<TextView>(R.id.tvBoardTitle)
        title.text = "Papan Hasil AI"
        loadBoard()
    }

    private fun loadBoard() {
        llBoard.removeAllViews()
        val prefs = getSharedPreferences("sm_prefs", MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("papan_cards", null) ?: "[]")
        if (arr.length() == 0) {
            val empty = TextView(this)
            empty.text = "Belum ada kartu di papan.\nHasil pencarian AI akan otomatis masuk ke papan."
            empty.textSize = 14f
            empty.setTextColor(android.graphics.Color.GRAY)
            empty.gravity = android.view.Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            empty.layoutParams = lp
            llBoard.addView(empty)
            return
        }
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            addCard(item)
        }
    }

    private fun addCard(item: JSONObject) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(8, 8, 8, 8)

        val container = LinearLayout(this)
        container.orientation = LinearLayout.HORIZONTAL

        val iv = ImageView(this)
        iv.layoutParams = LinearLayout.LayoutParams(80, 80)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setBackgroundColor(android.graphics.Color.LTGRAY)
        val img = item.optString("image")
        if (img.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                val bmp = ShopeeSearch.loadImage(img)
                if (bmp != null) {
                    withContext(Dispatchers.Main) { iv.setImageBitmap(bmp) }
                }
            }
        }
        container.addView(iv)

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val tvTitle = TextView(this)
        tvTitle.text = item.optString("title")
        tvTitle.textSize = 14f
        tvTitle.setTextColor(android.graphics.Color.BLACK)
        tvTitle.maxLines = 2
        texts.addView(tvTitle)
        container.addView(texts)

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.setPadding(0, 8, 0, 0)

        val btnOpen = TextView(this)
        btnOpen.text = "Buka di Shopee"
        btnOpen.textSize = 13f
        btnOpen.setTextColor(android.graphics.Color.BLUE)
        btnOpen.setPadding(0, 8, 12, 8)
        btnOpen.setOnClickListener { ShopeeSearch.openInShopee(this, item.optString("url")) }
        btnRow.addView(btnOpen)

        val btnDel = TextView(this)
        btnDel.text = "Hapus"
        btnDel.textSize = 13f
        btnDel.setTextColor(android.graphics.Color.RED)
        btnDel.setPadding(0, 8, 0, 8)
        btnDel.setOnClickListener { removeCard(item) }
        btnRow.addView(btnDel)

        row.addView(container)
        row.addView(btnRow)
        llBoard.addView(row)
    }

    private fun removeCard(item: JSONObject) {
        val prefs = getSharedPreferences("sm_prefs", MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("papan_cards", null) ?: "[]")
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i)
            if (it != null && it.optString("url") != item.optString("url")) {
                out.put(it)
            }
        }
        prefs.edit().putString("papan_cards", out.toString()).apply()
        Toast.makeText(this, "Dikeluarkan dari papan", Toast.LENGTH_SHORT).show()
        loadBoard()
    }
}
