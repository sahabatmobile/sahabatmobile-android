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

/**
 * Ringkasan Harian Otomatis — setiap pagi, app memposting notifikasi berisi:
 *  1. Berita terkini sesuai topik profil.
 *  2. Tugas hari ini (belum selesai).
 *  3. Catatan kesehatan terakhir.
 *  4. Pesan WhatsApp terakhir (24 jam) yang belum dibalas.
 *
 * Selain itu, topik di Watchlist dicek tiap hari; notifikasi terpisah hanya
 * muncul kalau ada judul baru yang belum pernah tercatat.
 */
class DailySummaryReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "sm_daily_summary"
        const val WATCH_CHANNEL_ID = "sm_watch_news"
        const val NOTIF_ID = 9999
        const val WATCH_NOTIF_ID = 10000

        const val EXTRA_ASK_TITLE = "ask_news_title"
        const val EXTRA_ASK_LINK = "ask_news_link"
        const val EXTRA_SPEAK_TITLE = "speak_news_title"

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
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runDaily(context)
            } catch (e: Exception) {
                Log.i("SM_DAILY", "onReceive failed: ${e.message}")
            } finally {
                schedule(context)
                pending.finish()
            }
        }
    }

    /** Tes langsung: buat & posting ringkasan sekarang (tanpa menunggu alarm). */
    fun testNow(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runDaily(context)
                Log.i("SM_DAILY", "testNow: summary posted")
            } catch (e: Exception) {
                Log.i("SM_DAILY", "testNow failed: ${e.message}")
            }
        }
    }

    private fun runDaily(context: Context) {
        val prefs = context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("daily_summary_enabled", true)) return
        val news = fetchNews(context, prefs)
        val summary = buildSummary(context, prefs, news)
        if (summary.isNotBlank()) postSummaryNotification(context, news, summary)
        checkWatchlist(context)
    }

    private fun fetchNews(context: Context, prefs: android.content.SharedPreferences): List<NewsItem> {
        val topic = NewsFeed.detectTopic(
            prefs.getString("profile_job", null),
            prefs.getString("profile_hobby", null),
            prefs.getString("profile_goal", null)
        )
        val items = try {
            NewsFeed.fetch(topic, 3)
        } catch (e: Exception) {
            emptyList()
        }
        val enriched = items.mapIndexed { i, n ->
            if (i == 0) n.copy(realLink = NewsFeed.resolveLink(n.link)) else n
        }
        if (enriched.isNotEmpty()) NewsFeed.saveToPrefs(context, topic, enriched)
        return enriched
    }

    private fun checkWatchlist(context: Context) {
        Watchlist.load(context).forEach { topic ->
            val items = try {
                NewsFeed.fetchByQuery(topic.query, 5)
            } catch (e: Exception) {
                emptyList()
            }
            val fresh = items.filter { n ->
                n.link !in topic.seen && n.title !in topic.seen
            }
            if (fresh.isNotEmpty()) {
                Watchlist.markSeen(context, topic, items.map { it.link })
                postWatchNotification(context, topic.label, fresh)
                Log.i("SM_DAILY", "watchlist '${topic.label}': ${fresh.size} berita baru")
            }
        }
    }

    private fun buildSummary(
        context: Context,
        prefs: android.content.SharedPreferences,
        news: List<NewsItem>
    ): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id"))
        sb.append("📅 ${dateFormat.format(Date())}\n")

        if (news.isNotEmpty()) {
            val topic = NewsFeed.detectTopic(
                prefs.getString("profile_job", null),
                prefs.getString("profile_hobby", null),
                prefs.getString("profile_goal", null)
            )
            sb.append("\n📰 Berita Terkini · ${NewsFeed.label(topic)}\n")
            news.forEach { n ->
                sb.append("• ${n.title}")
                if (n.source.isNotBlank()) sb.append(" — ${n.source}")
                if (n.timeAgo.isNotBlank()) sb.append(" (${n.timeAgo})")
                sb.append("\n")
            }
        }

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

        try {
            AssistantData.loadHealth(context).lastOrNull()?.let { note ->
                val s = note.summary()
                if (s.isNotBlank()) {
                    sb.append("\n🩺 Catatan kesehatan terakhir (${note.dateLabel()}):\n")
                    sb.append("• $s\n")
                }
            }
        } catch (_: Exception) {
        }

        val waIncoming = prefs.getString("wa_last_incoming", null)
        val waTs = prefs.getLong("wa_last_incoming_ts", 0L)
        if (!waIncoming.isNullOrBlank() && System.currentTimeMillis() - waTs < 24 * 60 * 60 * 1000L) {
            sb.append("\n💬 Pesan WA terakhir:\n")
            sb.append("• \"${waIncoming.take(60)}\"\n")
        }

        sb.append("\n🤖 Semangat menjalani hari ini!")
        return sb.toString()
    }

    private fun newsActions(context: Context, n: NewsItem?): List<NotificationCompat.Action> {
        if (n == null) return emptyList()
        val link = n.realLink.ifBlank { n.link }
        val readPi = PendingIntent.getActivity(
            context, 2001,
            Intent(Intent.ACTION_VIEW, Uri.parse(link)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val askIntent = Intent(context, NativeChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ASK_TITLE, n.title)
            putExtra(EXTRA_ASK_LINK, link)
        }
        val askPi = PendingIntent.getActivity(
            context, 2002, askIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val speakIntent = Intent(context, NativeChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SPEAK_TITLE, n.title + ". " + n.source)
        }
        val speakPi = PendingIntent.getActivity(
            context, 2003, speakIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return listOf(
            NotificationCompat.Action(android.R.drawable.ic_menu_view, "Baca", readPi),
            NotificationCompat.Action(android.R.drawable.ic_menu_edit, "Tanya AI", askPi),
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Dengarkan", speakPi)
        )
    }

    private fun ensureChannel(context: Context, id: String, name: String, desc: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = desc }
            )
        }
    }

    private fun postSummaryNotification(context: Context, news: List<NewsItem>, text: String) {
        ensureChannel(context, CHANNEL_ID, "Ringkasan Harian", "Ringkasan pagi: berita, tugas, kesehatan, dan pesan WA")
        val intent = Intent(context, ProfileActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ProfileActivity.EXTRA_TAB_TASKS, true)
        }
        val pi = PendingIntent.getActivity(
            context, 1002, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val top = news.firstOrNull()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("☀️ Ringkasan Hari Ini")
            .setContentText(if (top != null) top.title.take(120) else text.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
        newsActions(context, top).forEach { builder.addAction(it) }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, builder.build())
        Log.i("SM_DAILY", "summary notification posted")
    }

    private fun postWatchNotification(context: Context, label: String, items: List<NewsItem>) {
        ensureChannel(context, WATCH_CHANNEL_ID, "Watchlist Berita", "Berita baru dari topik yang kamu pantau")
        val top = items.firstOrNull() ?: return
        val text = buildString {
            append("Topik: ").append(label).append("\n\n")
            items.take(5).forEachIndexed { i, n ->
                append(i + 1).append(". ").append(n.title)
                if (n.source.isNotBlank()) append(" — ").append(n.source)
                append("\n")
            }
        }
        val pi = PendingIntent.getActivity(
            context, 1003,
            Intent(context, WatchlistActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, WATCH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🔔 Berita Baru: $label")
            .setContentText(top.title.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
        newsActions(context, top).forEach { builder.addAction(it) }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(WATCH_NOTIF_ID, builder.build())
    }
}
