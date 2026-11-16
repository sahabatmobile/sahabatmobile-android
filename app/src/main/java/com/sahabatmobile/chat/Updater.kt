package com.sahabatmobile.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pemeriksa pembaruan aplikasi lewat GitHub Releases.
 *
 * Alur: cek rilis terbaru -> bandingkan versi -> unduh APK -> pasang.
 * Pemasangan APK memakai [FileProvider] + Intent, sehingga pengguna tidak perlu
 * membuka browser atau mencari file APK manual.
 */
object Updater {

    private const val REPO = "sahabatmobile/sahabatmobile-android"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val UA = "SahabatMobile-Updater"

    data class Release(
        val tag: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long
    )

    /** Ambil rilis terbaru (bukan draft/prerelease). null = gagal. */
    fun latest(): Release? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val o = org.json.JSONObject(text)
            val tag = o.optString("tag_name")
            if (tag.isEmpty()) return null
            var url = ""
            var name = ""
            var size = 0L
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val n = a.optString("name")
                    if (n.endsWith(".apk")) {
                        url = a.optString("browser_download_url")
                        name = n
                        size = a.optLong("size")
                        break
                    }
                }
            }
            if (url.isEmpty()) return null
            Release(tag, o.optString("body"), url, name, size)
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** true jika [newTag] lebih baru dari versi terpasang. */
    fun isNewer(newTag: String, current: String): Boolean {
        fun parts(v: String) = v.trim().trimStart('v', 'V').split('.', '-', '_')
            .map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(newTag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Unduh APK di latar belakang.
     * @param listener (persen 0..100, null) saat berjalan; (100, file) saat selesai;
     *                 (-1, null) saat gagal.
     */
    fun download(context: Context, url: String, name: String, listener: (Int, File?) -> Unit) {
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 120000
                conn.setRequestProperty("User-Agent", UA)
                if (conn.responseCode != 200) {
                    listener(-1, null)
                    return@Thread
                }
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, name.ifEmpty { "update.apk" })
                val total = conn.contentLengthLong
                var read = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            read += n
                            val pct = if (total > 0) (read * 100 / total).toInt() else -1
                            listener(pct, null)
                        }
                    }
                }
                listener(100, out)
            } catch (e: Exception) {
                listener(-1, null)
            }
        }.start()
    }

    /** Buka pemasang APK bawaan Android. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Android 8+: pengguna harus mengizinkan pemasangan dari sumber ini. */
    fun ensureInstallAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        val pm = context.packageManager
        if (pm.canRequestPackageInstalls()) return true
        try {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName)
                )
            )
        } catch (e: Exception) {
        }
        return false
    }
}
