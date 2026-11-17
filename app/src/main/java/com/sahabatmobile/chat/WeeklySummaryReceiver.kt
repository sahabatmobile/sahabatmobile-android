package com.sahabatmobile.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Ringkasan otomatis tiap Minggu malam pukul 19.00. */
class WeeklySummaryReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "sm_weekly_summary"
        const val NOTIF_ID = 10001
        private const val HOUR = 19

        fun schedule(context: Context) {
            val prefs = context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("weekly_summary_enabled", true)
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 1004,
                Intent(context, WeeklySummaryReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (!enabled) {
                am.cancel(pi)
                return
            }
            val cal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                set(Calendar.HOUR_OF_DAY, HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.WEEK_OF_YEAR, 1)
            }
            try {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
            Log.i("SM_WEEKLY", "next=${SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(cal.time)}")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runWeekly(context)
            } catch (e: Exception) {
                Log.i("SM_WEEKLY", "failed: ${e.message}")
            } finally {
                schedule(context)
                pending.finish()
            }
        }
    }

    fun testNow(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runWeekly(context)
                Log.i("SM_WEEKLY", "testNow posted")
            } catch (e: Exception) {
                Log.i("SM_WEEKLY", "testNow failed: ${e.message}")
            }
        }
    }

    private fun runWeekly(context: Context) {
        val prefs = context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("weekly_summary_enabled", true)) return
        val text = build(context, prefs)
        post(context, text)
    }

    private fun build(context: Context, prefs: android.content.SharedPreferences): String {
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("d MMM", Locale("id"))
        sb.append("📊 Ringkasan Minggu Ini (").append(fmt.format(Date())).append(")\n")

        val topic = NewsFeed.detectTopic(
            prefs.getString("profile_job", null),
            prefs.getString("profile_hobby", null),
            prefs.getString("profile_goal", null)
        )
        val news = try {
            NewsFeed.fetch(topic, 2)
        } catch (e: Exception) {
            emptyList()
        }
        if (news.isNotEmpty()) {
            sb.append("\n📰 Berita · ${NewsFeed.label(topic)}\n")
            news.forEach { sb.append("• ").append(it.title).append("\n") }
        }

        val watched = Watchlist.load(context).take(3)
        if (watched.isNotEmpty()) {
            sb.append("\n🔔 Topik Dipantau\n")
            watched.forEach { t ->
                val top = try {
                    NewsFeed.fetchByQuery(t.query, 1).firstOrNull()
                } catch (e: Exception) {
                    null
                }
                sb.append("• ").append(t.label)
                if (top != null) sb.append(": ").append(top.title.take(90))
                sb.append("\n")
            }
        }

        val tasks = AssistantData.loadTasks(context)
        val done = tasks.count { it.done }
        sb.append("\n✅ Tugas: ").append(done).append(" selesai dari ").append(tasks.size).append("\n")

        val since = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        val fin = AssistantData.loadFin(context).filter { it.ts >= since }
        var income = 0.0
        var expense = 0.0
        fin.forEach {
            if (it.type == "income") income += it.amount else expense += it.amount
        }
        if (fin.isNotEmpty()) {
            sb.append(String.format(
                Locale("id"),
                "💰 Keuangan 7 hari: masuk Rp %,.0f · keluar Rp %,.0f\n",
                income, expense
            ))
        }

        try {
            AssistantData.loadHealth(context).lastOrNull()?.let { h ->
                val s = h.summary()
                if (s.isNotBlank()) sb.append("\n🩺 Kesehatan: ").append(s).append("\n")
            }
        } catch (_: Exception) {
        }

        sb.append("\n🤖 Minggu baru, mari mulai dengan ringan.")
        return sb.toString()
    }

    private fun post(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ringkasan Mingguan", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Ringkasan tiap Minggu malam" }
            )
        }
        val openIntent = Intent(context, NativeChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, 1005, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val wa = Intent(Intent.ACTION_VIEW, Uri.parse(
            "https://api.whatsapp.com/send?text=" +
                java.net.URLEncoder.encode(text.take(1200), "UTF-8")
        ))
        val sharePi = PendingIntent.getActivity(
            context, 1006, wa,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("📊 Ringkasan Minggu Ini")
            .setContentText(text.take(150))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_share, "Bagikan", sharePi)
        nm.notify(NOTIF_ID, builder.build())
    }
}
