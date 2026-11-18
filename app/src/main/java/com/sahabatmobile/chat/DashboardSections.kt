package com.sahabatmobile.chat

import android.content.Context

/** Pengaturan tampilan bagian berita / video YouTube di beranda. */
object DashboardSections {

    private const val PREFS = "sm_dashboard_sections"
    private const val KEY_NEWS = "show_news"
    private const val KEY_YT = "show_youtube"

    fun load(context: Context): Pair<Boolean, Boolean> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_NEWS, true) to p.getBoolean(KEY_YT, true)
    }

    fun save(context: Context, showNews: Boolean, showYoutube: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_NEWS, showNews)
            .putBoolean(KEY_YT, showYoutube)
            .apply()
    }
}
