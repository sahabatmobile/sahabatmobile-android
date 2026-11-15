package com.sahabatmobile.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Listener notifikasi WhatsApp → AI.
 *
 * Ketika ada pesan WhatsApp masuk (package com.whatsapp):
 *  1. Menyimpan pesan terakhir ke prefs.
 *  2. AI membuat saran balasan (pakai konteks pribadi + mode sales kalau keyword jualan).
 *  3. Menyimpan saran ke prefs (wa_last_suggestion).
 *  4. Kalau fitur "Auto-reply WhatsApp" aktif → langsung kirim saran via WhatsApp.
 *  5. Posting notifikasi status singkat (AI sempat merespons).
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processMutex = Mutex()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notif = sbn ?: return
        if (!notif.packageName.startsWith("com.whatsapp")) return
        val extras = notif.notification.extras
        val text = (
            extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence("android.intent.extra.SUBTEXT")?.toString()
                ?: ""
            ).trim()
        val incoming = text
        // Fitur #4: nama kontak (biasanya EXTRA_TITLE pada notifikasi WA)
        val contactName = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals(incoming, ignoreCase = true) }

        // SKIP notifikasi grup / summary (contoh: "5 pesan baru", "2 chat")
        if (incoming.isEmpty()) return
        if (incoming.contains("pesan baru") || incoming.contains("pesan dari") || incoming.contains("chat")) {
            Log.i("SM_WA_LISTENER", "WA_NOTIF_SKIP_GROUP pkg=${notif.packageName} incoming=<${incoming}>")
            return
        }

        Log.i("SM_WA_LISTENER", "WA_NOTIF pkg=${notif.packageName} incoming=<${incoming}>")

        val prefs = getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("wa_last_incoming", incoming)
            .putLong("wa_last_incoming_ts", System.currentTimeMillis())
            .apply()

        // Proses satu per satu (mutex) supaya tidak saling ganggu
        scope.launch {
            processMutex.lock()
            try {
                processWaNotification(notif, incoming, prefs, contactName)
            } finally {
                processMutex.unlock()
            }
        }
    }

    private suspend fun processWaNotification(
        notif: StatusBarNotification,
        incoming: String,
        prefs: android.content.SharedPreferences,
        contactName: String?
    ) {
        // Deteksi mode berdasarkan nomor WhatsApp (personal vs business)
        val isBusiness = notif.packageName.contains("w4b") || notif.packageName.contains("business")
        val modePosition = if (isBusiness) {
            prefs.getInt("wa_business_mode", 1) // default: Sales
        } else {
            prefs.getInt("wa_personal_mode", 0) // default: Personal
        }
        val modeLabel = when (modePosition) {
            0 -> "PERSONAL / CURHAT"
            1 -> "SALES / JUALAN"
            else -> "CAMPURAN (AI deteksi)"
        }

        // Deteksi sales: gunakan mode yang dipilih ATAU auto-detect jika campuran
        val sales = when (modePosition) {
            1 -> true // Sales mode
            0 -> false // Personal mode
            else -> WhatsAppAiClient.isSalesKeyword(incoming) // Auto-detect
        }

        // Cek apakah ini nomor DEFAULT (untuk auto-reply)
        // 0 = Keduanya (tanpa filter), 1 = Personal only, 2 = Business only
        val defaultNumber = prefs.getInt("wa_default_number", 0)
        val isDefaultNumber = when (defaultNumber) {
            0 -> true // Keduanya → auto-reply semua
            1 -> !isBusiness // Personal only
            2 -> isBusiness // Business only
            else -> true
        }

        Log.i("SM_WA_LISTENER", "WA_NOTIF pkg=${notif.packageName} mode=$modeLabel sales=$sales isDefault=$isDefaultNumber contact=${contactName ?: "-"}")

        // Fitur #9: Mode Fokus — jika aktif, balas dengan pesan fokus (tanpa AI)
        val now = System.currentTimeMillis()
        val focusUntil = prefs.getLong("focus_until", 0L)
        val focusOn = prefs.getBoolean("focus_mode", false) && now < focusUntil
        if (prefs.getBoolean("focus_mode", false) && !focusOn) {
            // kedaluwarsa → matikan otomatis
            prefs.edit().putBoolean("focus_mode", false).apply()
        }

        val reply = if (focusOn) {
            val msg = prefs.getString("focus_reply", null)?.takeIf { it.isNotBlank() }
                ?: getString(R.string.focus_default_reply)
            Log.i("SM_WA_LISTENER", "FOCUS_MODE active (until=$focusUntil) reply=focus message")
            msg
        } else runCatching {
            WhatsAppAiClient.generate(this@WhatsAppNotificationListener, incoming, sales = sales, contactName = contactName)
        }
            .onFailure { Log.i("SM_WA_LISTENER", "GENERATE_EXCEPTION: ${it.message}", it) }
            .getOrNull()
        Log.i("SM_WA_LISTENER", "WA_NOTIF_INCOMING pkg=${notif.packageName} text=$incoming sales=$sales reply=${reply?.take(60)}")
        var sent = false
        if (reply != null) {
            prefs.edit().putString("wa_last_suggestion", reply).apply()
            // AUTO-REPLY hanya untuk nomor DEFAULT
            if (isDefaultNumber && prefs.getBoolean(NativeChatActivity.KEY_WA_AUTO, false)) {
                // Kirim ke nomor yang BENAR berdasarkan pengaturan
                val waPackage = when (prefs.getInt("wa_default_number", 0)) {
                    1 -> "com.whatsapp" // Personal only
                    2 -> "com.whatsapp.w4b" // Business only
                    else -> null // Keduanya → share sheet
                }
                sent = WhatsAppAiClient.sendViaWhatsApp(this@WhatsAppNotificationListener, reply, waPackage = waPackage)
                Log.i("SM_WA_LISTENER", "AUTO_REPLY sent=$sent to=${waPackage ?: "share_sheet"} incoming=${incoming.take(60)} reply=${reply.take(60)}")
            } else {
                Log.i("SM_WA_LISTENER", "AUTO_REPLY_SKIP notDefault=$isDefaultNumber incoming=${incoming.take(60)}")
            }
            postStatusNotification(notif, incoming, reply, sent = sent, isBusiness = isBusiness)
        } else {
            Log.i("SM_WA_LISTENER", "WA_NOTIF_REPLY_EMPTY incoming=${incoming.take(60)}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Tidak dipakai
        return
    }

    private fun postStatusNotification(notif: StatusBarNotification?, incoming: String, reply: String?, sent: Boolean, isBusiness: Boolean = false) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "sm_wa_ai_channel"
            val channel = nm.getNotificationChannel(channelId)
            if (channel == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Saran AI WhatsApp",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "Notifikasi dari AI terkait saran balas WhatsApp" }
                )
            }
            val builder = NotificationCompat.Builder(this, channelId)
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            builder.setContentTitle("🤖 AI WhatsApp")
            builder.setContentText(
                when {
                    reply == null -> incoming
                    sent -> "AI sudah merespon: ${reply.take(140)} (terkirim ke WhatsApp)"
                    else -> "Saran AI: ${reply.take(140)}"
                }
            )
            builder.setAutoCancel(true)

            // ID unik setiap notif supaya bisa muncul berulang
            val notifId = System.currentTimeMillis().toInt()

            // Klik notifikasi → buka WhatsAppReplyActivity (lihat saran AI)
            val intent = android.content.Intent(this, WhatsAppReplyActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = android.app.PendingIntent.getActivity(
                this,
                notifId,
                intent,
                android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pending)

            // Tombol "Kirim" di notifikasi → buka WhatsApp dengan teks terisi (ke nomor DEFAULT)
            if (reply != null) {
                // Gunakan nomor DEFAULT dari pengaturan (bukan dari pesan masuk)
                val waPrefs = getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
                val defaultNumber = waPrefs.getInt("wa_default_number", 0) // 0=Keduanya, 1=Personal, 2=Business
                val waPackage = when (defaultNumber) {
                    1 -> "com.whatsapp" // Personal only
                    2 -> "com.whatsapp.w4b" // Business only
                    else -> null // Keduanya → share sheet (pilih aplikasi)
                }
                val waIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, reply)
                    if (waPackage != null) setPackage(waPackage)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val waPending = android.app.PendingIntent.getActivity(
                    this,
                    notifId + 1,
                    waIntent,
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.ic_menu_send,
                    "📤 Kirim ke WhatsApp",
                    waPending
                )
            }

            // Tombol "Salin" di notifikasi
            if (reply != null) {
                val copyIntent = android.content.Intent(this, WhatsAppReplyActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("copy_text", reply)
                }
                val copyPending = android.app.PendingIntent.getActivity(
                    this,
                    notifId + 2,
                    copyIntent,
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.ic_menu_edit,
                    "📋 Salin",
                    copyPending
                )
            }

            nm.notify(notifId, builder.build())
        } catch (e: Exception) {
            Log.i("SM_WA_LISTENER", "postStatus failed: ${e.message}")
        }
    }
}
