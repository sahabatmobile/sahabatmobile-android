package com.sahabatmobile.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WhatsApp Reply Assistant — fitur AI untuk membuat saran balasan
 * dari pesan yang masuk di WhatsApp.
 *
 * Flow manual:
 * 1. User isi pesan masuk (dari lawan/kolaborator/pelanggan).
 * 2. Tekan "Buatkan Saran AI" → AI membaca konteks pribadi + persona,
 *    lalu merespons apa yang harus dijawab.
 * 3. Hasil disarankan di layar, tinggal "Kirim ke WhatsApp".
 *
 * Flow otomatis (dipicu WhatsAppNotificationListener):
 * notifikasi WhatsApp masuk → AI auto-generate → (jika Auto-reply aktif) langsung kirim.
 */
class WhatsAppReplyActivity : AppCompatActivity() {

    private lateinit var etIncoming: EditText
    private lateinit var btnGenerate: Button
    private lateinit var btnCopy: Button
    private lateinit var btnSendWa: Button
    private lateinit var tvSuggestion: TextView
    private var suggestion: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whatsapp_reply)
        etIncoming = findViewById(R.id.etIncoming)
        btnGenerate = findViewById(R.id.btnGenerateReply)
        btnCopy = findViewById(R.id.btnCopy)
        btnSendWa = findViewById(R.id.btnSendWa)
        tvSuggestion = findViewById(R.id.tvSuggestion)
        btnGenerate.setOnClickListener { generateReply() }
        btnCopy.setOnClickListener { copyToClipboard() }
        btnSendWa.setOnClickListener { sendToWhatsApp() }

        // Disable tombol Salin & Kirim sampai ada saran
        btnCopy.isEnabled = false
        btnSendWa.isEnabled = false
        btnCopy.alpha = 0.5f
        btnSendWa.alpha = 0.5f

        // Handle copy_text dari notifikasi
        val copyText = intent.getStringExtra("copy_text")
        if (!copyText.isNullOrEmpty()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("saran_wa", copyText))
            tvSuggestion.text = copyText
            suggestion = copyText
            btnCopy.isEnabled = true
            btnSendWa.isEnabled = true
            btnCopy.alpha = 1.0f
            btnSendWa.alpha = 1.0f
            Toast.makeText(this, "Teks disalin ke clipboard!", Toast.LENGTH_SHORT).show()
            return
        }

        // AUTO-LOAD pesan WhatsApp terakhir dari listener
        val prefs = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
        val lastIncoming = prefs.getString("wa_last_incoming", null)
        val lastSuggestion = prefs.getString("wa_last_suggestion", null)

        if (!lastIncoming.isNullOrEmpty()) {
            etIncoming.setText(lastIncoming)
            // Jika sudah ada saran dari listener, tampilkan langsung
            if (!lastSuggestion.isNullOrEmpty()) {
                tvSuggestion.text = lastSuggestion
                suggestion = lastSuggestion
                btnCopy.isEnabled = true
                btnSendWa.isEnabled = true
                btnCopy.alpha = 1.0f
                btnSendWa.alpha = 1.0f
            } else {
                // Auto-generate saran
                generateReply()
            }
        }
    }

    private fun generateReply() {
        val incoming = etIncoming.text.toString().trim()
        if (incoming.isEmpty()) {
            Toast.makeText(this, R.string.wa_incoming_empty, Toast.LENGTH_SHORT).show()
            return
        }
        tvSuggestion.text = getString(R.string.wa_thinking)

        // Deteksi mode dari pengaturan (personal/business)
        val prefs = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
        val defaultNumber = prefs.getInt("wa_default_number", 0) // 0=Personal, 1=Business
        val isBusiness = defaultNumber == 1
        val modePosition = if (isBusiness) {
            prefs.getInt("wa_business_mode", 1)
        } else {
            prefs.getInt("wa_personal_mode", 0)
        }

        // Deteksi sales: gunakan mode yang dipilih ATAU auto-detect jika campuran
        val sales = when (modePosition) {
            1 -> true // Sales mode
            0 -> false // Personal mode
            else -> WhatsAppAiClient.isSalesKeyword(incoming) // Auto-detect
        }

        // Jalankan di background thread — network call TIDAK BOLEH di main thread!
        CoroutineScope(Dispatchers.IO).launch {
            val reply = WhatsAppAiClient.generate(this@WhatsAppReplyActivity, incoming, sales = sales)
            withContext(Dispatchers.Main) {
                suggestion = reply
                if (reply != null) {
                    tvSuggestion.text = reply
                    // Enable tombol Salin & Kirim
                    btnCopy.isEnabled = true
                    btnSendWa.isEnabled = true
                    btnCopy.alpha = 1.0f
                    btnSendWa.alpha = 1.0f
                } else {
                    tvSuggestion.text = "Gagal membuat saran. Coba lagi."
                }
                if (sales) {
                    Toast.makeText(this@WhatsAppReplyActivity, R.string.wa_sales_detected, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyToClipboard() {
        val text = suggestion
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Belum ada saran. Ketik pesan lalu tekan 'Buatkan Saran AI' dulu.", Toast.LENGTH_LONG).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("saran_wa", text))
        Toast.makeText(this, "Teks disalin ke clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun sendToWhatsApp() {
        val text = suggestion
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Belum ada saran. Ketik pesan lalu tekan 'Buatkan Saran AI' dulu.", Toast.LENGTH_LONG).show()
            return
        }
        // Kirim ke nomor yang benar berdasarkan pengaturan
        val prefs = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
        val defaultNumber = prefs.getInt("wa_default_number", 0) // 0=Keduanya, 1=Personal, 2=Business

        val waPackage = when (defaultNumber) {
            1 -> "com.whatsapp" // Personal only
            2 -> "com.whatsapp.w4b" // Business only
            else -> null // Keduanya → share sheet
        }

        if (WhatsAppAiClient.sendViaWhatsApp(this, text, waPackage = waPackage)) {
            Toast.makeText(this, R.string.wa_sent_toast, Toast.LENGTH_SHORT).show()
        } else {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("saran_wa", text))
            Toast.makeText(this, R.string.wa_send_fallback, Toast.LENGTH_SHORT).show()
        }
    }
}
