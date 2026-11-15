package com.sahabatmobile.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Helper AI untuk fitur utilitas (Kamera Solusi & Template Kerja):
 * - ask(): panggilan chat/completions sekali jalan, opsional dengan gambar (multimodal).
 * - Utilitas gambar: decode berskala, simpan ke cache, ubah ke data URI.
 */
object AiTools {

    /** Tanya AI sekali jalan (opsional dengan gambar). Return teks jawaban atau null. */
    fun ask(
        context: Context,
        system: String,
        user: String,
        imagePath: String? = null,
        maxTokens: Int = 1600
    ): String? {
        val prefs = context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(NativeChatActivity.KEY_API, null) ?: return null
        val model = prefs.getString(NativeChatActivity.KEY_MODEL, NativeChatActivity.DEFAULT_MODEL)
            ?: NativeChatActivity.DEFAULT_MODEL

        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        val userMsg: JSONObject = if (imagePath != null) {
            val dataUri = imageDataUri(imagePath)
            if (dataUri != null) {
                JSONObject().put("role", "user").put(
                    "content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", user))
                        .put(
                            JSONObject().put("type", "image_url").put(
                                "image_url", JSONObject().put("url", dataUri)
                            )
                        )
                )
            } else JSONObject().put("role", "user").put("content", user)
        } else JSONObject().put("role", "user").put("content", user)
        msgs.put(userMsg)

        val payload = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("stream", false)
            .put("max_tokens", maxTokens)

        return try {
            val conn = URL(NativeChatActivity.API_BASE + "/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            var result: String? = null
            if (code == 200) {
                result = JSONObject(conn.inputStream.bufferedReader().readText())
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
            } else {
                Log.i("SM_AITOOLS", "ASK_HTTP code=$code")
            }
            conn.disconnect()
            result?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.i("SM_AITOOLS", "ASK_ERR ${e.message}")
            null
        }
    }

    /** Ubah file gambar menjadi data URI base64 untuk konten multimodal. */
    fun imageDataUri(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        return try {
            val bytes = File(path).readBytes()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    /** Decode gambar dari Uri dengan skala maksimum tertentu (hemat memori). */
    fun decodeScaled(context: Context, uri: Uri, maxPx: Int): Bitmap? {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            while (largest / sample > maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            scaleToMax(bmp, maxPx)
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleToMax(src: Bitmap, maxPx: Int): Bitmap {
        val largest = maxOf(src.width, src.height)
        if (largest <= maxPx) return src
        val ratio = maxPx.toFloat() / largest
        return Bitmap.createScaledBitmap(
            src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true
        )
    }

    /** Simpan bitmap ke cache/images dan kembalikan path-nya. */
    fun saveImage(context: Context, bitmap: Bitmap): String? {
        return try {
            val dir = File(context.cacheDir, "images").apply { mkdirs() }
            val file = File(dir, "img_${UUID.randomUUID()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /** Tema markdown standar mengikuti warna aplikasi. */
    fun markdownTheme(context: Context): Markdown.Theme = Markdown.Theme(
        text = context.getColor(R.color.textPrimary),
        accent = context.getColor(R.color.primary),
        muted = context.getColor(R.color.textSecondary),
        codeBg = context.getColor(R.color.primaryTint),
        codeText = context.getColor(R.color.primaryDeep),
        bullet = context.getColor(R.color.primary),
        divider = context.getColor(R.color.stroke),
        tip = context.getColor(R.color.gold),
        quote = context.getColor(R.color.textSecondary)
    )
}
