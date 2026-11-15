package com.sahabatmobile.chat

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client AI untuk fitur AI Voice Call (percakapan suara langsung dengan AI):
 * - transcribe(): PCM 16 kHz mono -> WAV -> ASR via POST /api/v1/chat/completions (model ASR).
 * - generateReply(): teks hasil ASR -> balasan AI (reuse WhatsAppAiClient.generate).
 * Alur panggilan: user bicara -> ASR -> AI menjawab -> TTS (on-device) -> user bicara lagi.
 */
object VoiceAiClient {

    private const val SAMPLE_RATE = 16000

    /** TTS LongCat (OpenAI-compatible): suara lebih natural + voice clone. */
    const val LONGCAT_API_BASE = "https://api.longcat.chat/v1"
    const val KEY_LONGCAT_API = "longcat_api_key"
    const val KEY_LONGCAT_VOICE = "longcat_voice"

    /**
     * Ubah teks menjadi audio WAV via LongCat TTS (POST /audio/speech).
     * Return bytes WAV, atau null jika gagal / belum ada API key LongCat.
     */
    fun synthesize(context: Context, text: String): ByteArray? {
        val prefs = context.getSharedPreferences(NativeChatActivity.PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_LONGCAT_API, null)
        if (key.isNullOrBlank()) return null
        val voice = prefs.getString(KEY_LONGCAT_VOICE, null)?.takeIf { it.isNotBlank() } ?: "id"
        val payload = JSONObject()
            .put("input", text.take(600))
            .put("voice", voice)
            .put("response_format", "wav")
        return try {
            val conn = URL("$LONGCAT_API_BASE/audio/speech").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            val bytes = if (code == 200) conn.inputStream.use { it.readBytes() } else null
            conn.disconnect()
            if (code != 200) Log.i("SM_VOICE_CALL", "TTS_HTTP code=$code")
            bytes?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.i("SM_VOICE_CALL", "TTS_ERR ${e.message}")
            null
        }
    }

    /** Transkrip suara (PCM 16 kHz mono 16-bit) menjadi teks. Return null jika gagal. */
    fun transcribe(pcm: ByteArray, apiKey: String): String? {
        if (pcm.size < SAMPLE_RATE) return null
        val wav = wrapWav(pcm)
        val payload = JSONObject()
            .put("model", NativeChatActivity.ASR_MODEL)
            .put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content", JSONArray().put(
                            JSONObject().put("type", "input_audio").put(
                                "input_audio", JSONObject()
                                    .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
                                    .put("format", "wav")
                            )
                        )
                    )
                )
            )
        return try {
            val conn = URL(NativeChatActivity.API_BASE + "/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            var text: String? = null
            if (conn.responseCode == 200) {
                text = JSONObject(conn.inputStream.bufferedReader().readText())
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
            }
            conn.disconnect()
            text?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.i("SM_VOICE_CALL", "ASR_ERR ${e.message}")
            null
        }
    }

    /** Balasan AI dari teks yang sudah ditranskripsi (mode panggilan suara). */
    fun generateReply(context: Context, incoming: String): String? =
        WhatsAppAiClient.generate(context, incoming, voiceCall = true)

    private fun wrapWav(pcm: ByteArray): ByteArray {
        val dataLen = pcm.size
        val out = ByteArray(44 + dataLen)
        var pos = 0
        fun putStr(s: String) {
            for (c in s.toByteArray(Charsets.US_ASCII)) out[pos++] = c
        }
        fun putInt(v: Int) {
            out[pos++] = (v and 0xFF).toByte()
            out[pos++] = ((v shr 8) and 0xFF).toByte()
            out[pos++] = ((v shr 16) and 0xFF).toByte()
            out[pos++] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(v: Int) {
            out[pos++] = (v and 0xFF).toByte()
            out[pos++] = ((v shr 8) and 0xFF).toByte()
        }
        putStr("RIFF"); putInt(36 + dataLen); putStr("WAVE")
        putStr("fmt "); putInt(16); putShort(1); putShort(1)
        putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
        putStr("data"); putInt(dataLen)
        System.arraycopy(pcm, 0, out, 44, dataLen)
        return out
    }
}
