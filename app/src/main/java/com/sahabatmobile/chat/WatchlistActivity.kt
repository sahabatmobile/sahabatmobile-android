package com.sahabatmobile.chat

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class WatchlistActivity : AppCompatActivity() {

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watchlist)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val edtTopic = findViewById<EditText>(R.id.edtTopic)
        val btnAdd = findViewById<Button>(R.id.btnAdd)
        val txtEmpty = findViewById<TextView>(R.id.txtEmpty)
        val list = findViewById<LinearLayout>(R.id.listContainer)

        fun render() {
            list.removeAllViews()
            val topics = Watchlist.load(this)
            txtEmpty.visibility = if (topics.isEmpty()) View.VISIBLE else View.GONE
            topics.forEach { t ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(14, 12, 12, 12)
                    background = getDrawable(R.drawable.bg_chip)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 8 }
                }
                row.addView(TextView(this).apply {
                    text = "🔔"
                    textSize = 16f
                    setPadding(0, 0, dp(10), 0)
                })
                row.addView(TextView(this).apply {
                    text = t.label
                    textSize = 14f
                    setTextColor(getColor(R.color.textPrimary))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    maxLines = 2
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this).apply {
                    text = getString(R.string.watchlist_remove)
                    textSize = 11f
                    isAllCaps = false
                    minWidth = 0
                    minimumWidth = 0
                    setTextColor(getColor(R.color.errorText))
                    setPadding(12, 6, 12, 6)
                    background = getDrawable(R.drawable.bg_preview)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setOnClickListener {
                        Watchlist.remove(this@WatchlistActivity, t.query)
                        render()
                    }
                })
                list.addView(row)
            }
        }

        render()

        btnAdd.setOnClickListener {
            val q = edtTopic.text.toString()
            if (Watchlist.add(this, q)) {
                edtTopic.text.clear()
                render()
                Toast.makeText(this, R.string.watchlist_added, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.watchlist_dup, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
