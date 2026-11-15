package com.sahabatmobile.chat

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AssistantActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        applyWindowInsets()

        listContainer = findViewById(R.id.listContainer)
        listContainer.removeAllViews()

        CoroutineScope(Dispatchers.IO).launch {
            val cats = readCategories()
            withContext(Dispatchers.Main) {
                renderCategories(cats)
            }
        }
    }

    private fun renderCategories(cats: List<JSONObject>) {
        listContainer.removeAllViews()
        listContainer.addView(sectionTitle(getString(R.string.pick_assistant)))
        for (cat in cats) {
            val row = row(
                "${cat.optString("emoji")} ${cat.optString("nama")}",
                cat.optString("deskripsi").ifEmpty { getString(R.string.new_chat_hint) }
            )
            row.setOnClickListener { showSubs(cat) }
            listContainer.addView(row)
        }
    }

    private fun showSubs(cat: JSONObject) {
        val subs = cat.getJSONArray("sub")
        listContainer.removeAllViews()
        listContainer.addView(sectionTitle(getString(R.string.pick_assistant)))
        val back = row("‹  ${getString(R.string.back_to_list)}", cat.optString("nama"))
        back.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val cats = readCategories()
                withContext(Dispatchers.Main) { renderCategories(cats) }
            }
        }
        listContainer.addView(back)
        for (i in 0 until subs.length()) {
            val sub = subs.getJSONObject(i)
            val row = row(
                "${sub.optString("emoji")} ${sub.optString("nama")}",
                sub.optString("prompt").take(90)
            )
            row.setOnClickListener { choose(sub) }
            listContainer.addView(row)
        }
    }

    private fun sectionTitle(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(getColor(R.color.textSecondary))
        tv.textSize = 11.5f
        tv.letterSpacing = 0.12f
        tv.setPadding(dp(6), dp(4), dp(6), dp(12))
        return tv
    }

    private fun row(title: String, subtitle: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = getDrawable(R.drawable.bg_drawer_item_ripple)
        box.setPadding(dp(18), dp(14), dp(18), dp(14))
        box.isClickable = true

        val t = TextView(this)
        t.text = title
        t.setTextColor(getColor(R.color.textPrimary))
        t.textSize = 16f
        box.addView(t)

        if (subtitle.isNotBlank()) {
            val s = TextView(this)
            s.text = subtitle
            s.setTextColor(getColor(R.color.textSecondary))
            s.textSize = 13f
            s.setPadding(0, dp(4), 0, 0)
            box.addView(s)
        }

        val p = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = dp(8)
        box.layoutParams = p
        return box
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.rootContent)
        val toolbar = findViewById<View>(R.id.toolbar)
        val toolbarPadTop = toolbar.paddingTop
        val rootPadBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(
                toolbar.paddingLeft,
                toolbarPadTop + bars.top,
                toolbar.paddingRight,
                toolbar.paddingBottom
            )
            root.setPadding(
                root.paddingLeft,
                root.paddingTop,
                root.paddingRight,
                rootPadBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun readCategories(): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val stream: InputStream = assets.open("assistants.json")
        stream.use { s ->
            val body = s.bufferedReader().readText()
            val root = JSONObject(body)
            val arr = root.getJSONArray("kategori")
            for (i in 0 until arr.length()) result.add(arr.getJSONObject(i))
        }
        return result
    }

    private fun choose(sub: JSONObject) {
        val persona = JSONObject()
            .put("sub", sub.optString("nama"))
            .put("emoji", sub.optString("emoji"))
            .put("prompt", sub.optString("prompt"))
        getSharedPreferences("sm_prefs", MODE_PRIVATE).edit().putString("assistant", persona.toString()).apply()
        Toast.makeText(this, "Asisten: ${sub.optString("emoji")} ${sub.optString("nama")}", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
