package com.sahabatmobile.chat

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client AI untuk fitur WhatsApp Reply: membuat saran balasan dari pesan masuk.
 * Dipakai oleh:
 * - WhatsAppReplyActivity (manual: user ketik pesan → AI → tampilkan → kirim)
 * - WhatsAppNotificationListener (otomatis: notifikasi WA masuk → AI → saran → auto-kirim)
 */
object WhatsAppAiClient {

    private const val SALES_KEYWORDS = "jual,order,harga,promo,diskon,beli,discount,pesan,booking,ready,stok,barang,produk,toko,shop,belanja,checkout,pembayaran,bayar,transfer,cod,kirim,ongkir,gratis,bonus,cashback,flash sale,limited,terbatas,stock,available"

    fun isSalesKeyword(text: String): Boolean {
        val lower = text.lowercase()
        return SALES_KEYWORDS.split(",").map { it.trim() }.any { lower.contains(it) }
    }

    /**
     * Deteksi bahasa asing pada pesan pelanggan (fitur #2: Auto-Terjemahan).
     * Return nama bahasa jika terdeteksi BUKAN bahasa Indonesia, null jika Indonesia/tak pasti.
     */
    fun detectForeignLanguage(text: String): String? {
        // Deteksi huruf non-Latin dulu (paling akurat)
        if (text.any { it in '\u4E00'..'\u9FFF' }) return "Bahasa Mandarin"
        if (text.any { it in '\u3040'..'\u30FF' }) return "Bahasa Jepang"
        if (text.any { it in '\uAC00'..'\uD7AF' }) return "Bahasa Korea"
        if (text.any { it in '\u0600'..'\u06FF' }) return "Bahasa Arab"
        if (text.any { it in '\u0E00'..'\u0E7F' }) return "Bahasa Thai"
        if (text.any { it in '\u0400'..'\u04FF' }) return "Bahasa Rusia"

        // Deteksi bahasa Inggris lewat penanda kata
        val lower = text.lowercase()
        val enMarkers = listOf(
            "hello", "hi there", "how much", "the price", "price of", "order", "buy",
            "please", "thanks", "thank you", "can i", "do you", "do u", "i want",
            "i'm interested", "im interested", "shipping", "available", "in stock",
            "discount", "how long", "where are", "good morning", "good afternoon",
            "good evening", "sorry", "help me"
        )
        val idMarkers = listOf(
            "kak", "berapa", "harga", "mau", "bisa", "saya", "aku", "kamu", "banget",
            "dong", "gak", "nggak", "tuh", "nih", "sih", "gan", "min ", "barang",
            "kirim", "oke", "sip", "selamat", "terima kasih", "aja", "saja", "udah",
            "sudah", "gimana", "bagaimana", "siapa", "apa ", "itu", "ini"
        )
        val enCount = enMarkers.count { lower.contains(it) }
        val idCount = idMarkers.count { lower.contains(it) }
        return if (enCount >= 2 && enCount > idCount) "Bahasa Inggris" else null
    }

    // ============ Fitur #4: Persona per Kontak ============
    // Penyimpanan: prefs "wa_contact_personas" = [{"name":"Pak Iwan","mode":"sales"}]
    // mode: "sales" | "personal" | "formal" | "campuran"

    fun loadContactPersonas(context: Context): JSONArray = try {
        val prefs = context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
        JSONArray(prefs.getString("wa_contact_personas", "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    fun saveContactPersonas(context: Context, arr: JSONArray) {
        context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
            .edit().putString("wa_contact_personas", arr.toString()).apply()
    }

    fun addContactPersona(context: Context, name: String, mode: String) {
        val arr = loadContactPersonas(context)
        arr.put(JSONObject().put("name", name.trim()).put("mode", mode))
        saveContactPersonas(context, arr)
    }

    fun removeContactPersona(context: Context, index: Int) {
        val arr = JSONArray()
        val old = loadContactPersonas(context)
        for (i in 0 until old.length()) if (i != index) arr.put(old.getJSONObject(i))
        saveContactPersonas(context, arr)
    }

    /** Cari persona khusus untuk nama kontak (cocokkan tanpa membedakan huruf besar/kecil). */
    fun findContactPersona(context: Context, contactName: String?): JSONObject? {
        if (contactName.isNullOrBlank()) return null
        val lowerContact = contactName.lowercase().trim()
        val arr = loadContactPersonas(context)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val stored = o.optString("name").lowercase().trim()
            if (stored.isNotEmpty() &&
                (lowerContact == stored || lowerContact.contains(stored) || stored.contains(lowerContact))
            ) return o
        }
        return null
    }

    private fun personaStyleLabel(mode: String): String = when (mode) {
        "sales" -> "GAYA SALES: antusias, tawarkan produk & promo, panggil 'kak', jangan bilang AI"
        "personal" -> "GAYA TEMAN DEKAT: santai, hangat, bahasa sehari-hari, JANGAN jualan"
        "formal" -> "GAYA FORMAL: sopan, profesional, bahasa baku, tanpa emoji berlebihan"
        else -> "GAYA CAMPURAN: deteksi sendiri — jika bicara produk/harga → gaya sales, jika ngobrol biasa → gaya teman"
    }

    fun generate(context: Context, incoming: String, sales: Boolean = false, contactName: String? = null, voiceCall: Boolean = false): String? {
        val prefs = context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(NativeChatActivity.KEY_API, null) ?: return context.getString(R.string.error_generic)
        val model = prefs.getString(NativeChatActivity.KEY_MODEL, NativeChatActivity.DEFAULT_MODEL) ?: NativeChatActivity.DEFAULT_MODEL
        val assistant = JSONObject(prefs.getString(NativeChatActivity.KEY_ASSISTANT, "{}") ?: "{}")

        // Fitur #4: persona per kontak — override mode jika kontak terdaftar
        val persona = findContactPersona(context, contactName)
        val isSalesKeyword = when {
            persona != null -> when (persona.optString("mode")) {
                "sales" -> true
                "personal", "formal" -> false
                else -> WhatsAppAiClient.isSalesKeyword(incoming) // campuran
            }
            else -> sales || WhatsAppAiClient.isSalesKeyword(incoming)
        }
        if (persona != null) {
            Log.i("SM_WA_LISTENER", "CONTACT_PERSONA name=${persona.optString("name")} mode=${persona.optString("mode")}")
        }
        val lower = incoming.lowercase()
        val isPersonalKeyword = listOf("halo", "hai", "hi", "bro", "sis", "kak", "gan", "min", 
            "gimana", "bagaimana", "kabar", "apa kabar", "lagi apa", "ngapain", "bosen", "sedih", 
            "senang", "bahagia", "kecewa", "marah", "capek", "lelah", "butuh teman", "curhat", 
            "cerita", "share", "keluh", "kesah").any { lower.contains(it) }

        val sysBase: String = when {
            isSalesKeyword -> buildString {
                append("""
## KAMU ADALAH SALES / ADMIN TOKO ONLINE ⚡
Tugas utama kamu: MEMBANTU calon pelanggan dan MENINGKATKAN penjualan.

ATURAN WAJIB:
1. JANGAN PERNAH bilang kamu AI, asisten, atau bukan penjual. Kamu adalah SALES.
2. Sapa dengan "Halo kak! 😊"
3. Tawarkan bantuan: "Ada yang bisa saya bantu?"
4. Jika pelanggan tanya produk/harga → jawab dengan antusias & tawarkan promo
5. SINGKAT & JELAS (maksimal 3-4 kalimat)
6. Gunakan emoji untuk menarik perhatian 🎯✨
7. Selalu ada penawaran menarik: promo, diskon, bonus, free ongkir
8. Jika ada info produk → gunakan info tersebut untuk menjawab & MENGENALKAN produk
9. Jika ada FAQ → gunakan jawaban dari FAQ untuk pertanyaan yang sama

CONTOH GAYA BALASAN:
- "Halo kak! 😊 Terima kasih sudah menghubungi. Kami punya promo menarik hari ini lho! Mau tanya produk apa kak?"
- "Halo kak! Siap bantu! Untuk harga produk yang kakak maksud, bisa saya cek dulu ya. Boleh tahu nama produknya? 🎯"

JAWAB DALAM BAHASA INDONESIA.
""")
                // Tambah info produk jika ada
                val productInfo = prefs.getString("wa_product_info", "") ?: ""
                if (productInfo.isNotBlank()) {
                    append("\n\n## INFO PRODUK / JASA YANG DIJUAL:\n")
                    append(productInfo)
                }
                // Tambah FAQ jika ada
                val faqInfo = prefs.getString("wa_faq_info", "") ?: ""
                if (faqInfo.isNotBlank()) {
                    append("\n\n## FAQ / TANYA JAWAB:\n")
                    append(faqInfo)
                }
                append("\n\n## Konteks tambahan:\n")
                append(assistant.optString("prompt").ifEmpty { context.getString(R.string.persona_general_prompt) })
            }
            isPersonalKeyword -> buildString {
                append("""
## KAMU ADALAH TEMAN DEKAT 💬
Kamu sedang ngobrol dengan teman dekat. Balas dengan:
1. HANGAT & personal (bahasa sehari-hari)
2. EMPATIK & mendengarkan
3. SANTAI & natural
4. SINGKAT (2-3 kalimat)
5. JANGAN pakai bahasa formal atau jualan
6. Gunakan emoji seperlunya 😊

CONTOH: "Halo! Gimana kabarnya? 😊 Ada yang bisa dibantu?"
""")
                append("\n\n")
                append(assistant.optString("prompt").ifEmpty { context.getString(R.string.persona_general_prompt) })
            }
            else -> buildString {
                append("""
## KAMU ADALAH ASISTEN YANG RAMAH 🤝
Balas dengan sopan, jelas, dan membantu. 
1. Gunakan bahasa Indonesia yang baik
2. SINGKAT & langsung ke inti
3. Tawarkan bantuan lebih lanjut
""")
                append("\n\n")
                append(assistant.optString("prompt").ifEmpty { context.getString(R.string.persona_general_prompt) })
            }
        }

        // Fitur #2: Auto-Terjemahan — balas dalam bahasa pelanggan jika bukan Indonesia
        val autoTranslate = prefs.getBoolean("wa_auto_translate", true)
        val foreignLang: String? = if (autoTranslate) detectForeignLanguage(incoming) else null
        // Fitur #4: catatan gaya khusus kontak
        val personaNote: String = if (persona != null) {
            buildString {
                append("\n\n## KONTAK KHUSUS 👤\n")
                append("Nama kontak: ${persona.optString("name")}\n")
                append(personaStyleLabel(persona.optString("mode")) + "\n")
                append("Gunakan gaya ini untuk SEMUA balasan ke kontak ini.\n")
            }
        } else ""
        val sys: String = if (foreignLang != null) {
            Log.i("SM_WA_LISTENER", "LANG_DETECT=$foreignLang msg=${incoming.take(60)}")
            sysBase + personaNote + buildString {
                append("\n\n## ATURAN BAHASA WAJIB 🌍\n")
                append("Pelanggan menulis dalam $foreignLang.\n")
                append("WAJIB: balas SELURUH pesan dalam $foreignLang.\n")
                append("DILARANG membalas dalam bahasa Indonesia.\n")
                append("Tetap pertahankan gaya peran di atas (sales/teman), dan jika ada INFO PRODUK atau FAQ, artikan ke $foreignLang.\n")
            }
        } else sysBase + personaNote

        // Fitur AI Voice Call: beri tahu AI bahwa ini panggilan suara langsung
        val voiceCallBlock = if (voiceCall) {
            """
## 📞 MODE PANGGILAN SUARA (VOICE CALL) — WAJIB IKUTI
Kamu SEDANG MENELPON pengguna secara langsung lewat SUARA (bukan chat teks).
- Ucapan pengguna SUDAH ditranskripsi menjadi pesan teks — kamu BENAR-BENAR MENDENGAR suaranya.
- Balas seperti orang ngobrol di telepon: SINGKAT (1-3 kalimat), natural, jangan bertele-tele.
- DILARANG bilang kamu "hanya bisa baca teks", "tidak bisa mendengar suara", atau semacamnya. Kamu MENDENGAR suara pengguna.
- Jika ditanya "bisa dengar suara saya?" jawab yakin: "Ya, saya dengar kok!"
"""
        } else ""

        val sysFinal: String = voiceCallBlock + sys

        // Tambah tugas jika ada
        val tasks = AssistantData.loadTasks(context)
        val pending = tasks.filter { !it.done }.take(10)
        val sysWithTasks = if (pending.isNotEmpty()) {
            sysFinal + "\n\n## Tugas & Pengingat pengguna:\n" +
                pending.joinToString("\n") { "- ${it.text} (jam: ${if (it.at > 0L) it.timeLabel() else "-"})" }
        } else sysFinal
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", sysWithTasks))
            .put(JSONObject().put("role", "user").put("content", incoming))
        val webSearchParams = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("query", JSONObject().put("type", "string").put("description", "Kata kunci pencarian berita/informasi terbaru.")))
            .put("required", JSONArray().put("query"))
        val webTools = JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "web_search")
                .put("description", "Mencari informasi terbaru dari internet berdasarkan kata kunci yang diberikan.")
                .put("parameters", webSearchParams))
        val payload = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("stream", false)
            .put("max_tokens", 800)
            .put("tools", JSONArray().put(webTools))
            .put("tool_choice", "auto")

        val conn = URL(NativeChatActivity.API_BASE + "/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val code = conn.responseCode
        Log.i("SM_WA_LISTENER", "GENERATE_HTTP code=$code msg=${conn.responseMessage}")
        var content: String? = null
        if (code == 200) {
            val resp = JSONObject(conn.inputStream.bufferedReader().readText())
            val choices = resp.optJSONArray("choices")
            if (choices != null) {
                val choice = choices.getJSONObject(0)
                content = choice.optJSONObject("message").optString("content")
            }
        } else {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "(no body)"
            Log.i("SM_WA_LISTENER", "GENERATE_ERR body=${err.take(300)}")
        }
        val result = content?.trim()?.takeIf { it.isNotEmpty() }
        Log.i("SM_WA_LISTENER", "GENERATE_RESULT code=$code result=${result?.take(80) ?: "(null)"}")
        return if (code == 200) result else null
    }

    /** Kirim teks ke WhatsApp (prefilled composer) — dipakai auto-reply & manual. */
    fun sendViaWhatsApp(context: Context, text: String, waPackage: String? = null): Boolean {
        return try {
            val waIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                if (waPackage != null) setPackage(waPackage)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val waPending = android.app.PendingIntent.getActivity(
                context, 0, waIntent,
                android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            waPending.send(context, 0, null)
            true
        } catch (e: Exception) {
            // Fallback: share sheet (pilih aplikasi)
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val pending = android.app.PendingIntent.getActivity(
                    context, 0, intent,
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                pending.send(context, 0, null)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
}
