package com.sahabatmobile.chat

import android.graphics.Bitmap
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LeadingMarginSpan.Standard
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.net.Uri
import android.view.View

/**
 * Renderer markdown ringan untuk bubble jawaban AI.
 *
 * Mendukung: **tebal**, *miring*, `kode`, ### heading, daftar -, daftar 1.,
 * kutipan >, garis ---, serta label tips (Tips:, Penting:, Catatan:, Rekomendasi:).
 * Semua penanda markdown dibersihkan sehingga tidak pernah tampil mentah di layar.
 */
object Markdown {

    /** Penanda gambar markdown ![alt](url) — nanti diganti ImageSpan bitmap oleh activity. */
    class ImageRef(val url: String)

    /** Cache bitmap gambar yang pernah dimuat (agar tidak diunduh ulang). */
    val bitmapCache = mutableMapOf<String, Bitmap>()

    /** Span link yang membuka browser/app eksternal, aman untuk dipakai ulang. */
    class URLSpanCompat(private val url: String) : ClickableSpan() {
        override fun onClick(widget: View) {
            try {
                widget.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
            }
        }

        override fun updateDrawState(ds: android.text.TextPaint) {
            ds.isUnderlineText = false
        }
    }

    data class Theme(
        val text: Int,
        val accent: Int,
        val muted: Int,
        val codeBg: Int,
        val codeText: Int,
        val bullet: Int,
        val divider: Int,
        val tip: Int,
        val quote: Int
    )

    private val TIP_LABELS = listOf(
        "Tips:", "Tip:", "Penting:", "Catatan:", "Rekomendasi:", "Perhatian:",
        "Ingat:", "Alasan:", "Kesimpulan:", "Ringkasan:", "Langkah:"
    )

    private val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    fun render(src: String, theme: Theme): CharSequence {
        val out = SpannableStringBuilder()
        val lines = src.replace("\r\n", "\n").split("\n")
        var first = true

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            val trimmed = line.trim()

            if (!first) out.append("\n")

            if (trimmed.isEmpty()) {
                first = false
                i++
                continue
            }

            // Blok tabel markdown: | kolom | kolom |  +  |---|---|
            if (isTableRow(trimmed)) {
                val tableRows = mutableListOf<List<String>>()
                var j = i
                while (j < lines.size && isTableRow(lines[j].trim())) {
                    tableRows.add(splitTableRow(lines[j].trim()))
                    j++
                }
                appendTable(out, tableRows, theme)
                first = false
                i = j
                continue
            }

            // Garis pemisah
            if (trimmed.matches(Regex("^([-*_])\\s*\\1\\s*\\1[-*_\\s]*$"))) {
                val start = out.length
                out.append("─".repeat(18))
                out.setSpan(ForegroundColorSpan(theme.divider), start, out.length, SPAN)
                out.setSpan(RelativeSizeSpan(0.8f), start, out.length, SPAN)
                first = false
                i++
                continue
            }

            // Heading (### / ## / #)
            val headingMatch = Regex("^(#{1,4})\\s+(.*)$").find(trimmed)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val body = headingMatch.groupValues[2]
                if (!first) out.append("\n")
                val start = out.length
                out.append(body)
                out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, out.length, SPAN)
                out.setSpan(ForegroundColorSpan(theme.accent), start, out.length, SPAN)
                out.setSpan(RelativeSizeSpan(1.02f + (4 - level) * 0.035f), start, out.length, SPAN)
                first = false
                i++
                continue
            }

            // Kutipan
            if (trimmed.startsWith(">")) {
                val body = trimmed.removePrefix(">").trim()
                val barStart = out.length
                out.append("│  ")
                out.setSpan(ForegroundColorSpan(theme.bullet), barStart, out.length, SPAN)
                val bodyStart = out.length
                out.append(body)
                out.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), bodyStart, out.length, SPAN)
                out.setSpan(ForegroundColorSpan(theme.quote), bodyStart, out.length, SPAN)
                out.setSpan(Standard(0, 36), 0, out.length, SPAN)
                first = false
                i++
                continue
            }

            // Daftar berbutir (- / * / •)
            val bulletMatch = Regex("^[-*•]\\s+(.*)$").find(trimmed)
            if (bulletMatch != null) {
                val body = bulletMatch.groupValues[1]
                val markerStart = out.length
                out.append("•")
                out.setSpan(ForegroundColorSpan(theme.bullet), markerStart, out.length, SPAN)
                out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), markerStart, out.length, SPAN)
                out.append("  ")
                val bodyStart = out.length
                appendInline(out, body, theme)
                out.setSpan(Standard(28, 0), bodyStart, out.length, SPAN)
                first = false
                i++
                continue
            }

            // Daftar bernomor (1. / 1) )
            val numberMatch = Regex("^(\\d+)[\\.)]\\s+(.*)$").find(trimmed)
            if (numberMatch != null) {
                val num = numberMatch.groupValues[1]
                val body = numberMatch.groupValues[2]
                val numStart = out.length
                out.append(num)
                out.setSpan(ForegroundColorSpan(theme.bullet), numStart, out.length, SPAN)
                out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), numStart, out.length, SPAN)
                out.append(".  ")
                val bodyStart = out.length
                appendInline(out, body, theme)
                out.setSpan(Standard(32, 0), bodyStart, out.length, SPAN)
                first = false
                i++
                continue
            }

            // Baris biasa — cek label tips untuk pewarnaan
            val tipLabel = TIP_LABELS.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
            if (tipLabel != null) {
                val labelStart = out.length
                out.append(tipLabel)
                out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), labelStart, out.length, SPAN)
                out.setSpan(ForegroundColorSpan(theme.tip), labelStart, out.length, SPAN)
                appendInline(out, trimmed.substring(tipLabel.length), theme)
                first = false
                i++
                continue
            }

            appendInline(out, trimmed, theme)
            first = false
            i++
        }
        return out
    }

    /** Deteksi baris tabel markdown (memulai dengan "|"). */
    private fun isTableRow(trimmed: String): Boolean {
        if (!trimmed.startsWith("|")) return false
        return splitTableRow(trimmed).size >= 2
    }

    /** Pecah baris tabel menjadi daftar sel (membersihkan escape backslash). */
    private fun splitTableRow(trimmed: String): List<String> {
        var s = trimmed.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.substring(0, s.length - 1)
        return s.split("|").map { it.trim().replace("\\", "").trim() }
    }

    /** Deteksi baris separator tabel (|---|---|). */
    private fun isSeparatorRow(cells: List<String>): Boolean =
        cells.isNotEmpty() && cells.all { it.isNotEmpty() && it.matches(Regex("^:?-{2,}:?$")) }

    /**
     * Render tabel markdown menjadi daftar ringkas yang enak dibaca di layarHp:
     * judul kolom bold berwarna aksen, lalu tiap baris "label — nilai".
     */
    private fun appendTable(out: SpannableStringBuilder, rawRows: List<List<String>>, theme: Theme) {
        val rows = rawRows.filter { it.any { cell -> cell.isNotEmpty() } }
        if (rows.isEmpty()) return

        var header: List<String>? = null
        val body = mutableListOf<List<String>>()

        if (rows.size >= 2 && isSeparatorRow(rows[1])) {
            header = rows[0]
            body.addAll(rows.drop(2))
        } else {
            body.addAll(rows)
        }

        // Judul kolom
        if (header != null) {
            val title = header.filter { it.isNotEmpty() }.joinToString("  ·  ")
            if (title.isNotEmpty()) {
                val start = out.length
                out.append(title)
                out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, out.length, SPAN)
                out.setSpan(ForegroundColorSpan(theme.accent), start, out.length, SPAN)
                out.setSpan(RelativeSizeSpan(0.95f), start, out.length, SPAN)
                out.append("\n")
                val divStart = out.length
                out.append("─".repeat(16))
                out.setSpan(ForegroundColorSpan(theme.divider), divStart, out.length, SPAN)
                out.setSpan(RelativeSizeSpan(0.75f), divStart, out.length, SPAN)
                out.append("\n")
            }
        }

        for ((index, cells) in body.withIndex()) {
            if (index > 0) out.append("\n")
            val clean = cells.filter { it.isNotEmpty() }
            if (clean.isEmpty()) continue

            val markerStart = out.length
            out.append("•")
            out.setSpan(ForegroundColorSpan(theme.bullet), markerStart, out.length, SPAN)
            out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), markerStart, out.length, SPAN)
            out.append("  ")

            val bodyStart = out.length
            if (clean.size == 2) {
                // Dua kolom → "label — nilai"
                appendInline(out, clean[0], theme)
                val labelEnd = out.length
                out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), bodyStart, labelEnd, SPAN)
                out.setSpan(ForegroundColorSpan(theme.accent), bodyStart, labelEnd, SPAN)
                out.append(" — ")
                appendInline(out, clean[1], theme)
            } else {
                appendInline(out, clean[0], theme)
                val labelEnd = out.length
                out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), bodyStart, labelEnd, SPAN)
                out.setSpan(ForegroundColorSpan(theme.accent), bodyStart, labelEnd, SPAN)
                for (cell in clean.drop(1)) {
                    out.append("  ·  ")
                    appendInline(out, cell, theme)
                }
            }
            out.setSpan(Standard(26, 0), bodyStart, out.length, SPAN)
        }
    }

    /** Teks sebaris: **tebal**, *miring*, `kode`, __tebal__, gambar ![alt](url), link [teks](url). */
    private val IMG_RX = Regex("!\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)")
    private val LINK_RX = Regex("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")
    private val URL_RX = Regex("(https?://[^\\s`*_<()]{8,})")

    private fun appendInline(out: SpannableStringBuilder, line: String, theme: Theme) {
        // Sisipkan gambar markdown sebagai placeholder \uFFFC + ImageRef
        if (IMG_RX.containsMatchIn(line)) {
            var pos = 0
            for (m in IMG_RX.findAll(line)) {
                if (m.range.first > pos) appendInline(out, line.substring(pos, m.range.first), theme)
                val start = out.length
                out.append("\uFFFC")
                out.setSpan(ImageRef(m.groupValues[1]), start, out.length, SPAN)
                pos = m.range.last + 1
            }
            if (pos < line.length) appendInline(out, line.substring(pos), theme)
            return
        }
        // Link markdown [teks](url) → teks berwarna aksen yang bisa di-tap
        if (LINK_RX.containsMatchIn(line)) {
            var pos = 0
            for (m in LINK_RX.findAll(line)) {
                if (m.range.first > pos) appendInline(out, line.substring(pos, m.range.first), theme)
                val start = out.length
                out.append(m.groupValues[1])
                out.setSpan(URLSpanCompat(m.groupValues[2]), start, out.length, SPAN)
                out.setSpan(ForegroundColorSpan(theme.accent), start, out.length, SPAN)
                pos = m.range.last + 1
            }
            if (pos < line.length) appendInline(out, line.substring(pos), theme)
            return
        }
        // URL mentah → bisa di-tap
        if (URL_RX.containsMatchIn(line)) {
            var pos = 0
            for (m in URL_RX.findAll(line)) {
                if (m.range.first > pos) appendInline(out, line.substring(pos, m.range.first), theme)
                val start = out.length
                val url = m.groupValues[1].trimEnd('.', ',', ')')
                out.append(url)
                out.setSpan(URLSpanCompat(url), start, start + url.length, SPAN)
                pos = m.range.first + url.length
            }
            if (pos < line.length) appendInline(out, line.substring(pos), theme)
            return
        }
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '`' -> {
                    val end = line.indexOf('`', i + 1)
                    if (end > i) {
                        val inner = line.substring(i + 1, end)
                        val start = out.length
                        out.append(inner)
                        out.setSpan(TypefaceSpan("monospace"), start, out.length, SPAN)
                        out.setSpan(BackgroundColorSpan(theme.codeBg), start, out.length, SPAN)
                        out.setSpan(ForegroundColorSpan(theme.codeText), start, out.length, SPAN)
                        i = end + 1
                    } else {
                        // Backtick nyaring (tanda escape dari model) → dibuang, tidak ditampilkan
                        i++
                    }
                }

                (ch == '*' || ch == '_') && i + 1 < line.length && line[i + 1] == ch -> {
                    val end = line.indexOf("$ch$ch", i + 2)
                    if (end > i) {
                        val inner = line.substring(i + 2, end)
                        val start = out.length
                        out.append(inner)
                        out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, out.length, SPAN)
                        out.setSpan(ForegroundColorSpan(theme.accent), start, out.length, SPAN)
                        i = end + 2
                    } else {
                        out.append(ch); i++
                    }
                }

                ch == '*' || ch == '_' -> {
                    val end = line.indexOf(ch, i + 1)
                    if (end > i) {
                        val inner = line.substring(i + 1, end)
                        val start = out.length
                        out.append(inner)
                        out.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), start, out.length, SPAN)
                        out.setSpan(ForegroundColorSpan(theme.muted), start, out.length, SPAN)
                        i = end + 1
                    } else {
                        out.append(ch); i++
                    }
                }

                else -> {
                    out.append(ch); i++
                }
            }
        }
    }
}
