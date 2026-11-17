package com.sahabatmobile.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class HabitResult(
    val text: String,
    val topics: List<Pair<String, Int>>,
    val complaints: List<String>,
    val messageCount: Int
)

object HabitInsight {

    private const val PREFS = "sm_habit_insight"
    private const val KEY_TEXT = "text"
    private const val KEY_TS = "ts"
    private const val TTL = 3 * 24 * 60 * 60 * 1000L

    private val TOPICS = linkedMapOf(
        "bisnis & penghasilan" to Regex("(?i)bisnis|jualan|jual|shopee|seller|omzet|harga|pajak|margin|pelanggan|pembeli|toko|produk|rupiah|rp\\s?\\d|uang|investasi|saham|katalog|konten"),
        "kesehatan" to Regex("(?i)sakit|dokter|gigi|tensi|darah|tidur|olahraga|diet|berat badan|alergi|kontrol|obat|kesehatan|capek|lelah|stres"),
        "keluarga" to Regex("(?i)anak|istri|suami|ayah|ibu|keluarga|rumah|orang tua|saudara"),
        "makanan" to Regex("(?i)makan|lapar|menu|resep|masak|kopi|es campur|restoran|makanan|camilan"),
        "lokasi & perjalanan" to Regex("(?i)pergi|jalan|malang|surabaya|jakarta|maps|rute|liburan|hotel|terdekat|lokasi"),
        "kerja & teknologi" to Regex("(?i)kerja|rapat|tugas|deadline|kode|aplikasi|\\bai\\b|chat|whatsapp|\\bwa\\b|laptop|hp")
    )

    private val COMPLAINTS = linkedMapOf(
        "capek" to Regex("(?i)capek|lelah|kelelahan|penat"),
        "susah tidur" to Regex("(?i)susah tidur|insomnia|nggak bisa tidur|tidur larut|begadang"),
        "stres & gelisah" to Regex("(?i)stres|gelisah|khawatir|deg.?degan|overthinking|sedih|bosen"),
        "bingung" to Regex("(?i)bingung|ribet|gimana ya|tidak tahu|gak tau| doubt"),
        "biaya mahal" to Regex("(?i)mahal|kemahalan|ongkos|biaya|hibah"),
        "telat & menunda" to Regex("(?i)telat|terlambat|nunda|nunda-nunda|deadline mepet")
    )

    fun userMessages(context: Context): List<String> {
        val raw = context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
            .getString(NativeChatActivity.KEY_SESSIONS, "[]") ?: "[]"
        val out = mutableListOf<String>()
        try {
            val sessions = JSONArray(raw)
            for (i in 0 until sessions.length()) {
                val msgs = sessions.optJSONObject(i)?.optJSONArray("messages") ?: continue
                for (j in 0 until msgs.length()) {
                    val m = msgs.optJSONObject(j) ?: continue
                    if (m.optString("role") == "user") {
                        val t = m.optString("content").trim()
                        if (t.length > 2 && !t.startsWith("Jelaskan secara profesional berita ini")) {
                            out.add(t)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.takeLast(250)
    }

    fun analyze(context: Context): HabitResult {
        val messages = userMessages(context)
        val all = messages.joinToString(" ").lowercase(Locale.getDefault())
        val topics = TOPICS.map { it.key to countMatches(it.value, all) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
        val complaints = COMPLAINTS.filter { it.value.containsMatchIn(all) }.keys.take(3)

        val body = buildString {
            if (topics.isNotEmpty()) {
                append("Paling sering dibahas: ")
                append(topics.joinToString(", ") { "${it.first} (${it.second}×) " }.trim())
                append(".\n")
            }
            if (complaints.isNotEmpty()) {
                append("Yang sering kamu keluhkan: ").append(complaints.joinToString(", ")).append(".\n")
            }
            if (isEmpty()) append("Belum cukup riwayat chat untuk mengenali kebiasaanmu.")
        }
        return HabitResult(body, topics, complaints, messages.size)
    }

    private fun countMatches(regex: Regex, text: String): Int =
        regex.findAll(text).count()

    fun cached(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = p.getLong(KEY_TS, 0L)
        if (ts <= 0L || System.currentTimeMillis() - ts > TTL) return null
        return p.getString(KEY_TEXT, null)
    }

    fun save(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TEXT, text)
            .putLong(KEY_TS, System.currentTimeMillis())
            .apply()
    }

    /** Ringkas pakai AI; kalau gagal, pakai analisis lokal. */
    fun summarizeWithAi(context: Context, messages: List<String>): String? {
        if (messages.isEmpty()) return null
        val prefs = context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString(NativeChatActivity.KEY_API, null) ?: return null
        if (key.isBlank()) return null
        val model = prefs.getString(NativeChatActivity.KEY_MODEL, NativeChatActivity.DEFAULT_MODEL)
            ?: NativeChatActivity.DEFAULT_MODEL
        val sample = messages.takeLast(120).joinToString("\n").take(7000)
        val payload = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("max_tokens", 300)
            .put("messages", JSONArray()
                .put(JSONObject()
                    .put("role", "system")
                    .put("content", "Kamu menganalisis riwayat chat seorang pengguna asisten pribadi Indonesia. " +
                        "Ringkas dalam MAKSIMAL 3 baris pendek: (1) topik yang paling sering dibicarakan, " +
                        "(2) hal yang paling sering dikeluhkan, (3) saran singkat bagaimana asisten bisa lebih membantu. " +
                        "Gunakan bahasa Indonesia yang hangat, konkret, tanpa daftar panjang, tanpa markdown."))
                .put(JSONObject().put("role", "user").put("content", sample)))
        val conn = URL("${NativeChatActivity.API_BASE}/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 12000
        conn.readTimeout = 25000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $key")
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            val choices = JSONObject(body).optJSONArray("choices") ?: return null
            choices.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim()?.ifBlank { null }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
