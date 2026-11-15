package com.sahabatmobile.chat

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Fitur AI Voice Call: panggilan suara langsung dengan AI.
 * Alur siklus: AI menyapa (TTS) -> mendengarkan user (AudioRecord) ->
 * ASR -> AI menjawab -> dibacakan (TTS) -> mendengarkan lagi ... hingga tombol Akhiri.
 */
class VoiceCallActivity : AppCompatActivity() {

    enum class CallState { IDLE, SPEAKING, LISTENING, THINKING }

    private var state = CallState.IDLE
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var apiKey: String? = null

    private var recorder: AudioRecord? = null
    private var recordBuffer: ByteArrayOutputStream? = null
    private var recordThread: Thread? = null
    private var isRecording = false
    private var mediaPlayer: MediaPlayer? = null

    private var timerHandler: Handler? = null
    private var callSeconds = 0
    private var timerRunning = false

    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var btnMic: ImageButton
    private lateinit var btnEndCall: Button
    private lateinit var btnVoiceSettings: Button

    private val ttsListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            runOnUiThread {
                if (state != CallState.IDLE) {
                    state = CallState.SPEAKING
                    updateUi()
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            runOnUiThread {
                if (state == CallState.SPEAKING) startListening()
            }
        }

        override fun onError(utteranceId: String?) {
            runOnUiThread {
                if (state == CallState.SPEAKING) startListening()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_call)

        apiKey = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
            .getString(NativeChatActivity.KEY_API, null)

        statusText = findViewById(R.id.tvCallStatus)
        timerText = findViewById(R.id.tvCallTimer)
        btnMic = findViewById(R.id.btnVoiceCallMic)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnVoiceSettings = findViewById(R.id.btnVoiceSettings)

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                // WAJIB: pasang listener agar onDone terpicu saat AI selesai bicara
                tts?.setOnUtteranceProgressListener(ttsListener)
                val result = tts?.setLanguage(Locale("id", "ID"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.setLanguage(Locale.getDefault())
                }
            }
        }

        btnMic.setOnClickListener { toggleMic() }
        btnEndCall.setOnClickListener { endCall() }
        btnVoiceSettings.setOnClickListener { showVoiceSettings() }
        updateUi()
    }

    /** Setelah izin mikrofon diberikan, otomatis lanjutkan panggilan yang tertunda. */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            if (timerRunning && state != CallState.SPEAKING) startListening()
        } else if (requestCode == REQ_AUDIO) {
            Toast.makeText(this, R.string.voice_permission, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleMic() {
        when (state) {
            CallState.IDLE -> startCall()
            CallState.LISTENING -> stopListeningAndRespond()
            CallState.SPEAKING -> {
                // Potong AI yang sedang bicara, langsung dengarkan
                try {
                    tts?.stop()
                } catch (_: Exception) {
                }
                try {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                } catch (_: Exception) {
                }
                mediaPlayer = null
                startListening()
            }
            CallState.THINKING -> {
                // Tunggu AI selesai berpikir
            }
        }
    }

    private fun startCall() {
        if (apiKey.isNullOrEmpty()) {
            Toast.makeText(this, R.string.voice_call_error_no_key, Toast.LENGTH_LONG).show()
            return
        }
        callSeconds = 0
        timerRunning = true
        timerText.text = "00:00"
        timerHandler = Handler(Looper.getMainLooper())
        timerHandler?.post(object : Runnable {
            override fun run() {
                if (!timerRunning) return
                callSeconds++
                timerText.text = String.format(
                    Locale.getDefault(), "%02d:%02d", callSeconds / 60, callSeconds % 60
                )
                timerHandler?.postDelayed(this, 1000)
            }
        })
        speak(getString(R.string.voice_call_greeting))
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            state = CallState.IDLE
            updateUi()
            return
        }
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                NativeChatActivity.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                Toast.makeText(this, R.string.voice_not_available, Toast.LENGTH_SHORT).show()
                return
            }
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                NativeChatActivity.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                Toast.makeText(this, R.string.voice_not_available, Toast.LENGTH_SHORT).show()
                return
            }
            recorder = rec
            recordBuffer = ByteArrayOutputStream()
            isRecording = true
            state = CallState.LISTENING
            updateUi()
            rec.startRecording()
            recordThread = Thread {
                val buf = ByteArray(minBuf)
                while (isRecording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) recordBuffer?.write(buf, 0, n)
                }
            }.also { it.start() }
        } catch (e: Exception) {
            Log.i("SM_VOICE_CALL", "REC_ERR ${e.message}")
            Toast.makeText(this, R.string.voice_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopListeningAndRespond() {
        isRecording = false
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recordThread?.join(800)
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        recordThread = null

        val pcm = recordBuffer?.toByteArray() ?: return
        recordBuffer = null
        if (pcm.size < NativeChatActivity.SAMPLE_RATE) {
            Toast.makeText(this, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
            startListening()
            return
        }

        state = CallState.THINKING
        updateUi()
        CoroutineScope(Dispatchers.IO).launch {
            val text = VoiceAiClient.transcribe(pcm, apiKey ?: return@launch)
            val reply = if (text.isNullOrEmpty()) {
                null
            } else {
                VoiceAiClient.generateReply(this@VoiceCallActivity, text)
            }
            withContext(Dispatchers.Main) {
                if (state == CallState.IDLE) return@withContext
                if (reply.isNullOrEmpty()) {
                    Toast.makeText(this@VoiceCallActivity, R.string.voice_asr_error, Toast.LENGTH_SHORT).show()
                    startListening()
                } else {
                    speak(reply)
                }
            }
        }
    }

    private fun speak(text: String) {
        val clean = text
            .replace(Regex("[*#`_~|>\\[\\]]"), "")   // hapus karakter markdown
            .replace(Regex("\\([^)]*\\)"), "")        // hapus catatan dalam kurung
            .replace(Regex("[\\p{So}\\p{Sk}\\p{Cn}]"), " ") // hapus emoji/simbol
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isEmpty()) {
            startListening()
            return
        }
        state = CallState.SPEAKING
        updateUi()

        // 1) Coba TTS LongCat (suara natural / voice clone) — async
        CoroutineScope(Dispatchers.IO).launch {
            val wav = VoiceAiClient.synthesize(this@VoiceCallActivity, clean)
            withContext(Dispatchers.Main) {
                if (state != CallState.SPEAKING) return@withContext
                if (wav != null) {
                    playWav(wav, clean)
                } else {
                    // 2) Fallback: TTS on-device
                    speakOnDevice(clean)
                }
            }
        }
    }

    /** Putar audio WAV hasil LongCat TTS via MediaPlayer. */
    private fun playWav(wav: ByteArray, fallbackText: String) {
        try {
            val tmp = java.io.File.createTempFile("voice_call", ".wav", cacheDir)
            tmp.writeBytes(wav)
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tmp.absolutePath)
                setOnCompletionListener {
                    release()
                    mediaPlayer = null
                    tmp.delete()
                    if (state == CallState.SPEAKING) startListening()
                }
                setOnErrorListener { _, _, _ ->
                    release()
                    mediaPlayer = null
                    tmp.delete()
                    if (state == CallState.SPEAKING) speakOnDevice(fallbackText)
                    true
                }
                prepare()
                start()
            }
        } catch (_: Exception) {
            speakOnDevice(fallbackText) // fallback: tidak bisa putar audio LongCat
        }
    }

    /** Fallback: bacakan pakai TTS on-device Android. */
    private fun speakOnDevice(text: String?) {
        if (text.isNullOrEmpty() || !ttsReady) {
            startListening()
            return
        }
        tts?.setSpeechRate(1.05f)
        tts?.speak(text.take(600), TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** Dialog pengaturan suara AI: API key LongCat + voice ID (voice clone). */
    private fun showVoiceSettings() {
        val prefs = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val keyInput = EditText(this).apply {
            hint = getString(R.string.voice_call_settings_key_hint)
            setText(prefs.getString(VoiceAiClient.KEY_LONGCAT_API, "") ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val voiceInput = EditText(this).apply {
            hint = getString(R.string.voice_call_settings_voice_hint)
            setText(prefs.getString(VoiceAiClient.KEY_LONGCAT_VOICE, "") ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        container.addView(keyInput)
        container.addView(voiceInput)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.voice_call_settings_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.edit()
                    .putString(VoiceAiClient.KEY_LONGCAT_API, keyInput.text.toString().trim())
                    .putString(VoiceAiClient.KEY_LONGCAT_VOICE, voiceInput.text.toString().trim())
                    .apply()
                Toast.makeText(this, R.string.voice_call_settings_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateUi() {
        statusText.text = when (state) {
            CallState.IDLE -> getString(R.string.voice_call_status_idle)
            CallState.SPEAKING -> getString(R.string.voice_call_status_speaking)
            CallState.LISTENING -> getString(R.string.voice_call_status_listening)
            CallState.THINKING -> getString(R.string.voice_call_status_thinking)
        }
        btnMic.setBackgroundResource(
            if (state == CallState.LISTENING) R.drawable.bg_mic_active else R.drawable.bg_icon_button
        )
    }

    private fun endCall() {
        state = CallState.IDLE
        timerRunning = false
        timerHandler?.removeCallbacksAndMessages(null)
        isRecording = false
        try {
            recorder?.stop()
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        endCall()
    }

    override fun onDestroy() {
        timerRunning = false
        timerHandler?.removeCallbacksAndMessages(null)
        isRecording = false
        try {
            recorder?.stop()
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        super.onDestroy()
    }

    companion object {
        private const val REQ_AUDIO = 2001
        private const val UTTERANCE_ID = "sm_voice_call"
    }
}
