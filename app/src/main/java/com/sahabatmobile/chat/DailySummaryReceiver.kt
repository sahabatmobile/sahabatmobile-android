package com.sahabatmobile.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Ringkasan Harian Otomatis — setiap pagi, app memposting notifikasi berisi:
 *  1. Tugas hari ini (belum selesai).
 *  2. Catatan kesehatan terakhir.
 *  3. Pesan WhatsApp terakhir (24 jam) yang belum dibalas.
 *
 * Dipicu oleh AlarmManager; receiver ini menjadwalkan ulang dirinya untuk esok hari.
 */
class DailySummaryReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "sm_daily_summary"
        const val NOTIF_ID = 9999

        /** Jadwalkan alarm pagi berikutnya sesuai jam pengaturan. */
        fun schedule(context: Context) {
            val prefs = context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("daily_summary_enabled", true)
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

            val pi = PendingIntent.getBroadcast(
                context, 1001,
                Intent(context, DailySummaryReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (!enabled) {
                am.cancel(pi)
                Log.i("SM_DAILY", "schedule: disabled → alarm cancelled")
                return
            }

            val hour = prefs.getInt("daily_summary_hour", 7)
            val minute = prefs.getInt("daily_summary_minute", 0)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            // setExactAndAllowWhileIdle agar tetap jalan di Doze; fallback set()
            try {
                am.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi
                )
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
            Log.i("SM_DAILY", "schedule: next=${SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(cal.time)}")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("SM_DAILY", "onReceive fired")
        try {
            val prefs = context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("daily_summary_enabled", true)) return

            val summary = buildSummary(context, prefs)
            if (summary.isNotBlank()) {
                postSummaryNotification(context, summary)
            }
        } catch (e: Exception) {
            Log.i("SM_DAILY", "onReceive failed: ${e.message}")
        } finally {
            // Jadwalkan ulang untuk esok hari
            schedule(context)
        }
    }

    /** Tes langsung: buat & posting ringkasan sekarang (tanpa menunggu alarm). */
    fun testNow(context: Context) {
        try {
            val prefs = context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
            val summary = buildSummary(context, prefs)
            if (summary.isNotBlank()) {
                postSummaryNotification(context, summary)
                Log.i("SM_DAILY", "testNow: summary posted")
            }
        } catch (e: Exception) {
            Log.i("SM_DAILY", "testNow failed: ${e.message}")
        }
    }

    private fun buildSummary(context: Context, prefs: android.content.SharedPreferences): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id"))
        sb.append("📅 ${dateFormat.format(Date())}\n")

        // 1. Tugas hari ini
        val tasks = AssistantData.loadTasks(context)
        val pending = tasks.filter { !it.done }
        if (pending.isNotEmpty()) {
            sb.append("\n✅ Tugas hari ini (${pending.size}):\n")
            pending.take(5).forEach { t ->
                sb.append("• ${t.text}")
                if (t.at > 0L) sb.append(" (${t.timeLabel()})")
                sb.append("\n")
            }
            if (pending.size > 5) sb.append("• ... dan ${pending.size - 5} lainnya\n")
        } else {
            sb.append("\n✅ Tidak ada tugas terjadwal hari ini 🎉\n")
        }

        // 2. Catatan kesehatan terakhir
        try {
            val health = AssistantData.loadHealth(context)
            health.lastOrNull()?.let { note ->
                val s = note.summary()
                if (s.isNotBlank()) {
                    sb.append("\n🩺 Catatan kesehatan terakhir (${note.dateLabel()}):\n")
                    sb.append("• $s\n")
                }
            }
        } catch (_: Exception) {
        }

        // 3. Pesan WA terakhir (24 jam terakhir)
        val waIncoming = prefs.getString("wa_last_incoming", null)
        val waTs = prefs.getLong("wa_last_incoming_ts", 0L)
        if (!waIncoming.isNullOrBlank() && System.currentTimeMillis() - waTs < 24 * 60 * 60 * 1000L) {
            sb.append("\n💬 Pesan WA terakhir:\n")
            sb.append("• \"${waIncoming.take(60)}\"\n")
        }

        sb.append("\n🤖 Semangat menjalani hari ini!")
        return sb.toString()
    }

    private fun postSummaryNotification(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = nm.getNotificationChannel(CHANNEL_ID)
        if (channel == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Ringkasan Harian",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Ringkasan pagi: tugas, kesehatan, dan pesan WA" }
            )
        }
        // Klik notifikasi → buka tab Tugas
        val intent = Intent(context, ProfileActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ProfileActivity.EXTRA_TAB_TASKS, true)
        }
        val pi = PendingIntent.getActivity(
            context, 1002, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("☀️ Ringkasan Hari Ini")
            .setContentText(text.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
        nm.notify(NOTIF_ID, builder.build())
        Log.i("SM_DAILY", "summary notification posted")
    }
}
