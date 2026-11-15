package com.sahabatmobile.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fitur #3: Pesan Terjadwal WhatsApp.
 * User menulis pesan + memilih waktu → di waktu itu muncul notifikasi berisi
 * tombol "📤 Kirim ke WhatsApp" (buka WA dengan pesan terisi) dan "📋 Salin".
 *
 * Penyimpanan: JSON array di prefs "wa_scheduled":
 *   [{"id":"sch_...","text":"...","at":epochMs}]
 */
class ScheduledMessageReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "sm_wa_scheduled"
        const val KEY_STORE = "wa_scheduled"

        private fun prefs(context: Context) =
            context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)

        fun loadAll(context: Context): JSONArray = try {
            JSONArray(prefs(context).getString(KEY_STORE, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }

        fun saveAll(context: Context, arr: JSONArray) {
            prefs(context).edit().putString(KEY_STORE, arr.toString()).apply()
        }

        /** Tambah pesan terjadwal + langsung jadwalkan alarmnya. */
        fun add(context: Context, text: String, at: Long): JSONObject {
            val item = JSONObject()
                .put("id", "sch_" + System.currentTimeMillis())
                .put("text", text)
                .put("at", at)
            val arr = loadAll(context)
            arr.put(item)
            saveAll(context, arr)
            scheduleItem(context, item)
            Log.i("SM_WA_SCHED", "add: ${text.take(40)} at=$at")
            return item
        }

        /** Hapus dari penyimpanan + batalkan alarm. */
        fun remove(context: Context, id: String) {
            val arr = JSONArray()
            val old = loadAll(context)
            for (i in 0 until old.length()) {
                val o = old.getJSONObject(i)
                if (o.optString("id") != id) arr.put(o)
            }
            saveAll(context, arr)
            Log.i("SM_WA_SCHED", "remove: id=$id")
        }

        private fun alarmPendingIntent(context: Context, item: JSONObject): PendingIntent {
            val intent = Intent(context, ScheduledMessageReceiver::class.java).apply {
                putExtra("sched_id", item.optString("id"))
                putExtra("sched_text", item.optString("text"))
            }
            return PendingIntent.getBroadcast(
                context, item.optString("id").hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun cancelAlarm(context: Context, item: JSONObject) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.cancel(alarmPendingIntent(context, item))
        }

        fun scheduleItem(context: Context, item: JSONObject) {
            val at = item.optLong("at")
            if (at <= System.currentTimeMillis()) return // sudah lewat, abaikan
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = alarmPendingIntent(context, item)
            try {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            }
            Log.i("SM_WA_SCHED", "scheduleItem: id=${item.optString("id")} at=$at")
        }

        /** Jadwalkan ulang semua pesan yang masih tersimpan (dipanggil saat app dibuka). */
        fun scheduleAll(context: Context) {
            val arr = loadAll(context)
            for (i in 0 until arr.length()) scheduleItem(context, arr.getJSONObject(i))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("sched_id") ?: return
        val text = intent.getStringExtra("sched_text") ?: return
        Log.i("SM_WA_SCHED", "onReceive fired id=$id text=${text.take(40)}")
        try {
            postNotification(context, text)
        } catch (e: Exception) {
            Log.i("SM_WA_SCHED", "onReceive failed: ${e.message}")
        }
        // Hapus dari daftar karena sudah terkirim (notifikasinya)
        remove(context, id)
    }

    private fun postNotification(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Pesan Terjadwal WA",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Pengingat pesan WhatsApp terjadwal" }
            )
        }

        val prefs = prefs(context)

        // Aksi 1: Kirim ke WhatsApp (paket sesuai nomor default; null = share sheet/Keduanya)
        val waPackage = when (prefs.getInt("wa_default_number", 0)) {
            1 -> "com.whatsapp"
            2 -> "com.whatsapp.w4b"
            else -> null
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (waPackage != null) setPackage(waPackage)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val piSend = PendingIntent.getActivity(
            context, 2001, sendIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Aksi 2: Salin + buka layar reply
        val copyIntent = Intent(context, WhatsAppReplyActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("copy_text", text)
        }
        val piCopy = PendingIntent.getActivity(
            context, 2002, copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("⏰ Pesan Terjadwal Siap Dikirim")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(piCopy)
            .addAction(0, "📤 Kirim ke WhatsApp", piSend)
            .addAction(0, "📋 Salin", piCopy)
            .setAutoCancel(true)
        nm.notify(System.currentTimeMillis().toInt(), builder.build())
        Log.i("SM_WA_SCHED", "notification posted")
    }
}
