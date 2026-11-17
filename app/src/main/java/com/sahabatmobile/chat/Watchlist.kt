package com.sahabatmobile.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WatchTopic(
    val query: String,
    val label: String = query,
    val seen: List<String> = emptyList()
)

object Watchlist {

    private const val PREFS = "sm_watchlist"
    private const val KEY = "topics"

    fun load(context: Context): List<WatchTopic> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val seenArr = o.optJSONArray("seen") ?: JSONArray()
                WatchTopic(
                    query = o.optString("query"),
                    label = o.optString("label").ifBlank { o.optString("query") },
                    seen = (0 until seenArr.length()).map { seenArr.optString(it) }
                )
            }.filter { it.query.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, topics: List<WatchTopic>) {
        val arr = JSONArray()
        topics.forEach { t ->
            val seenArr = JSONArray()
            t.seen.forEach { seenArr.put(it) }
            arr.put(JSONObject()
                .put("query", t.query)
                .put("label", t.label)
                .put("seen", seenArr))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        val topics = load(context).toMutableList()
        if (topics.any { it.query.equals(q, true) }) return false
        topics.add(0, WatchTopic(q, q, emptyList()))
        save(context, topics)
        return true
    }

    fun remove(context: Context, query: String) {
        save(context, load(context).filter { !it.query.equals(query, true) })
    }

    fun markSeen(context: Context, topic: WatchTopic, links: List<String>) {
        val merged = (links + topic.seen).distinct().take(40)
        save(context, load(context).map {
            if (it.query.equals(topic.query, true)) it.copy(seen = merged) else it
        })
    }
}
