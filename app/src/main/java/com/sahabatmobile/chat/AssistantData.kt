package com.sahabatmobile.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * Data pendukung "asisten pribadi":
 * - Tugas & pengingat (reminder) yang bisa dibuat user
 * - Catatan kesehatan harian (tidur, berat, tensi, suasana hati)
 *
 * Disimpan di SharedPreferences sebagai JSON agar ringan dan offline.
 */
object AssistantData {

    const val KEY_TASKS = "assistant_tasks"
    const val KEY_HEALTH = "assistant_health_log"
    const val KEY_FIN = "fin_entries"

    data class Task(
        val id: String = UUID.randomUUID().toString(),
        val text: String,
        val at: Long = 0L,          // epoch ms waktu pengingat; 0 = tanpa jam
        val done: Boolean = false,
        val repeatDaily: Boolean = false
    ) {
        fun timeLabel(): String {
            if (at <= 0L) return ""
            val cal = Calendar.getInstance().apply { timeInMillis = at }
            return String.format(
                "%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE)
            )
        }
    }

    data class HealthNote(
        val id: String = UUID.randomUUID().toString(),
        val ts: Long = System.currentTimeMillis(),
        val sleepHours: Float? = null,
        val weightKg: Float? = null,
        val bp: String? = null,
        val mood: String? = null,
        val note: String? = null
    ) {
        fun dateLabel(): String {
            val cal = Calendar.getInstance().apply { timeInMillis = ts }
            return String.format(
                "%d %s",
                cal.get(Calendar.DAY_OF_MONTH),
                arrayOf(
                    "Jan", "Feb", "Mar", "Apr", "Mei", "Jun",
                    "Jul", "Agu", "Sep", "Okt", "Nov", "Des"
                )[cal.get(Calendar.MONTH)]
            )
        }

        fun summary(): String {
            val parts = mutableListOf<String>()
            sleepHours?.let { parts.add("tidur ${trimNumber(it)} jam") }
            weightKg?.let { parts.add("berat ${trimNumber(it)} kg") }
            bp?.takeIf { it.isNotBlank() }?.let { parts.add("tensi $it") }
            mood?.takeIf { it.isNotBlank() }?.let { parts.add("suasana: $it") }
            note?.takeIf { it.isNotBlank() }?.let { parts.add("keluhan: $it") }
            return parts.joinToString(", ")
        }

        private fun trimNumber(v: Float): String =
            if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.1f", v)
    }

    // ---------------- TUGAS ----------------

    fun loadTasks(ctx: Context): MutableList<Task> {
        val out = mutableListOf<Task>()
        try {
            val arr = JSONArray(
                ctx.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_TASKS, "[]") ?: "[]"
            )
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text")
                if (text.isBlank()) continue
                out.add(
                    Task(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        text = text,
                        at = o.optLong("at", 0L),
                        done = o.optBoolean("done", false),
                        repeatDaily = o.optBoolean("repeat", false)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveTasks(ctx: Context, list: List<Task>) {
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("text", t.text)
                    .put("at", t.at)
                    .put("done", t.done)
                    .put("repeat", t.repeatDaily)
            )
        }
        ctx.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TASKS, arr.toString()).apply()
    }

    // ---------------- CATATAN KESEHATAN ----------------

    fun loadHealth(ctx: Context): MutableList<HealthNote> {
        val out = mutableListOf<HealthNote>()
        try {
            val arr = JSONArray(
                ctx.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_HEALTH, "[]") ?: "[]"
            )
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    HealthNote(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        ts = o.optLong("ts", System.currentTimeMillis()),
                        sleepHours = if (o.has("sleep")) o.optDouble("sleep").toFloat() else null,
                        weightKg = if (o.has("weight")) o.optDouble("weight").toFloat() else null,
                        bp = o.optString("bp").ifEmpty { null },
                        mood = o.optString("mood").ifEmpty { null },
                        note = o.optString("note").ifEmpty { null }
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveHealth(ctx: Context, list: List<HealthNote>) {
        val arr = JSONArray()
        for (h in list) {
            val o = JSONObject()
                .put("id", h.id)
                .put("ts", h.ts)
            h.sleepHours?.let { o.put("sleep", it.toDouble()) }
            h.weightKg?.let { o.put("weight", it.toDouble()) }
            h.bp?.let { o.put("bp", it) }
            h.mood?.let { o.put("mood", it) }
            h.note?.let { o.put("note", it) }
            arr.put(o)
        }
        ctx.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HEALTH, arr.toString()).apply()
    }

    // ---------------- CATATAN KEUANGAN (AI Tracker) ----------------

    data class FinEntry(
        val id: String = UUID.randomUUID().toString(),
        val type: String, // "expense" | "income"
        val amount: Double,
        val desc: String,
        val ts: Long = System.currentTimeMillis()
    )

    fun loadFin(ctx: Context): MutableList<FinEntry> {
        val out = mutableListOf<FinEntry>()
        try {
            val arr = JSONArray(
                ctx.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_FIN, "[]") ?: "[]"
            )
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    FinEntry(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        type = o.optString("type", "expense"),
                        amount = o.optDouble("amount", 0.0),
                        desc = o.optString("desc"),
                        ts = o.optLong("ts", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveFin(ctx: Context, list: List<FinEntry>) {
        val arr = JSONArray()
        for (f in list) {
            val o = JSONObject()
                .put("id", f.id)
                .put("type", f.type)
                .put("amount", f.amount)
                .put("desc", f.desc)
                .put("ts", f.ts)
            arr.put(o)
        }
        ctx.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FIN, arr.toString()).apply()
    }
}
