package com.sahabatmobile.chat

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Fitur #8: Widget Home Screen 📱
 * Menampilkan: tanggal, tugas hari ini (belum selesai), dan total keuangan bulan ini.
 * Tap widget → langsung buka chat AI.
 */
class AssistantWidgetProvider : AppWidgetProvider() {

    companion object {
        /** Perbarui semua instance widget (dipanggil dari app saat data berubah). */
        fun updateAll(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, AssistantWidgetProvider::class.java)
                )
                for (id in ids) mgr.updateAppWidget(id, buildViews(context))
            } catch (_: Exception) {
            }
        }

        fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_assistant)

            // Tanggal hari ini
            views.setTextViewText(
                R.id.tvWidgetDate,
                SimpleDateFormat("EEEE, d MMM", Locale("id")).format(Date())
            )

            // Tugas hari ini (belum selesai)
            val tasks = AssistantData.loadTasks(context).filter { !it.done }
            val taskBody = if (tasks.isEmpty()) "Tidak ada tugas hari ini 🎉"
            else tasks.take(4).joinToString("\n") {
                "• ${it.text}${if (it.at > 0L) " (${it.timeLabel()})" else ""}"
            }
            views.setTextViewText(
                R.id.tvWidgetTasks,
                "✅ Tugas (${tasks.size})\n$taskBody"
            )

            // Keuangan bulan ini
            val fin = AssistantData.loadFin(context)
            val cal = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val monthStart = cal.timeInMillis
            var expense = 0.0
            var income = 0.0
            for (f in fin) {
                if (f.ts >= monthStart) {
                    if (f.type == "expense") expense += f.amount else income += f.amount
                }
            }
            views.setTextViewText(
                R.id.tvWidgetFin,
                String.format(
                    Locale("id"), "💰 Keluar Rp %,.0f · Masuk Rp %,.0f", expense, income
                )
            )

            // Tap widget → buka chat AI
            val openIntent = Intent(context, NativeChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 3001, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)
            return views
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }
}
