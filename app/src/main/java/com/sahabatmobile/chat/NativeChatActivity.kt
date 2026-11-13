package com.sahabatmobile.chat

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class NativeChatActivity : AppCompatActivity() {

    private data class ModelItem(val id: String, val name: String, val inPlan: Boolean? = null)

    private data class AccountStatus(
        val hasPackage: Boolean,
        val name: String?,
        val credit: Double,
        val until: String?
    )

    /**
     * Peta model yang ditanggung paket — disalin dari model_config tabel `packages`
     * (gabungan regular + highspeed). Sumber: database SahabatMobile.
     */
    private val PACKAGE_MODELS: Map<String, List<String>?> = mapOf(
        "hemat" to listOf(
            "mimo-v2.5", "mimo-v2.5-pro", "mimo-v2.5-pro-ultraspeed", "mimo-v2.6-flash",
            "mimo-v2.6-pro", "mimo-v2.5-asr", "mimo-v2.5-tts", "mimo-v2.5-tts-voiceclone",
            "mimo-v2.5-tts-voicedesign"
        ),
        "coding" to listOf(
            "mimo-v2.5", "mimo-v2.5-pro", "deepseek-v3.2", "deepseek-v4-flash", "qwen3.7-flash"
        ),
        "minimax" to listOf(
            "MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.5", "MiniMax-M2.1", "MiniMax-M2",
            "M2.7-highspeed", "M2.5-highspeed", "M2.1-highspeed"
        ),
        "pro" to listOf(
            "mimo-v2.5", "mimo-v2.5-pro", "deepseek-v4-pro", "deepseek-v3.2",
            "claude-3-haiku", "claude-haiku-4.5", "gpt-4o-mini", "gpt-4.1-mini",
            "gemini-2.5-flash", "qwen3.7-flash", "llama-3.3-70b", "qwen3-32b"
        ),
        "bisnis" to null,
        "claude_starter" to listOf("claude-haiku-5-5"),
        "claude_regular" to listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-sonnet-5"),
        "claude_dev" to listOf(
            "claude-haiku-5-5", "claude-sonnet-5-5", "claude-sonnet-5", "claude-opus-4-8"
        ),
        "claude_studio" to listOf(
            "claude-haiku-5-5", "claude-sonnet-5-5", "claude-sonnet-5",
            "claude-opus-4-8", "claude-fable-5-1"
        ),
        "claude_pro" to listOf(
            "claude-haiku-5-5", "claude-sonnet-5-5", "claude-sonnet-5",
            "claude-opus-4-8", "claude-fable-5-1"
        ),
        "asian_starter" to listOf("deepseek/deepseek-flash"),
        "asian_coder" to listOf("hy4-preview", "glm-5.3"),
        "longcat" to listOf("LongCat-2.5-Preview", "LongCat-2.0")
    )

    private val PACKAGE_NAMES: Map<String, String> = mapOf(
        "hemat" to "Paket Hemat",
        "coding" to "Paket Coding",
        "minimax" to "Paket MiniMax",
        "pro" to "Paket Pro",
        "bisnis" to "Paket Bisnis",
        "claude_starter" to "Claude Starter",
        "claude_regular" to "Claude Regular",
        "claude_dev" to "Claude Dev",
        "claude_studio" to "Claude Studio",
        "claude_pro" to "Claude Pro",
        "asian_starter" to "Asian AI Starter",
        "asian_coder" to "Asian AI Coder",
        "longcat" to "Paket LongCat"
    )

    companion object {
        const val API_BASE = "https://www.sahabatmobile.com/api/v1"
        const val DEFAULT_MODEL = "google/gemini-2.5-flash"
        const val PREFS = "sm_prefs"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_MODEL_SET = "model_manual"
        const val KEY_TYPE = "key_type"
        const val KEY_NAME = "owner_name"
        const val KEY_SESSIONS = "sessions"
        const val KEY_ASSISTANT = "assistant"
        const val KEY_ACCESS = "model_access"
        const val KEY_ACCESS_TS = "model_access_ts"
        const val KEY_PLAN_NAME = "plan_name"
        const val KEY_ALLOWED = "allowed_models"
        const val KEY_PAYG = "payg_key"
        const val REQ_AUDIO = 1001
        const val SAMPLE_RATE = 16000
        const val ASR_MODEL = "mimo-v2.5-asr"
    }

    private lateinit var bubbleContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var btnAttach: ImageButton
    private lateinit var btnRemoveImage: ImageButton
    private lateinit var attachPreview: View
    private lateinit var previewImage: ImageView
    private lateinit var modelPill: TextView
    private lateinit var recordBar: View
    private lateinit var recordTimer: TextView
    private lateinit var recordLevel: ProgressBar
    private lateinit var progress: ProgressBar
    private lateinit var onboardingView: ScrollView
    private lateinit var welcomeView: LinearLayout
    private lateinit var welcomeScroll: android.widget.ScrollView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerList: LinearLayout

    private val history = JSONArray()
    private var sendJob: Job? = null
    private var typingAnim: ObjectAnimator? = null
    private var apiKey: String? = null
    private var model: String = DEFAULT_MODEL
    private var assistant: JSONObject = JSONObject()
    private var pendingImagePath: String? = null
    private var cameraUri: Uri? = null
    private var speech: SpeechRecognizer? = null
    private var modelsCache: List<ModelItem> = emptyList()
    private var recorder: AudioRecord? = null
    private var recordBuffer: ByteArrayOutputStream? = null
    private var recordThread: Thread? = null
    private var recordTimerHandler: android.os.Handler? = null
    private var recordStartedAt = 0L
    private var isRecording = false
    private var lastErrorCode: String? = null
    private var keyType: String? = null
    private var packageName: String? = null
    private var packageKey: String? = null
    private var allowedModels: List<String>? = null
    private var isPaygKey = false
    private var showAllModels = false

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) processImageUri(uri)
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) cameraUri?.let { processImageUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_native_chat)

        applyWindowInsets()

        findViewById<TextView>(R.id.btnMenu).setOnClickListener { v ->
            PopupMenu(this, v).apply {
                menuInflater.inflate(R.menu.menu_native_chat, menu)
                setOnMenuItemClickListener { h -> handleMenuItem(h.itemId) }
            }.show()
        }
        bubbleContainer = findViewById(R.id.bubbleContainer)
        scrollView = findViewById(R.id.scrollView)
        input = findViewById(R.id.input)
        btnSend = findViewById(R.id.btnSend)
        btnMic = findViewById(R.id.btnMic)
        btnAttach = findViewById(R.id.btnAttach)
        btnRemoveImage = findViewById(R.id.btnRemoveImage)
        attachPreview = findViewById(R.id.attachPreview)
        previewImage = findViewById(R.id.previewImage)
        modelPill = findViewById(R.id.modelPill)
        recordBar = findViewById(R.id.recordBar)
        recordTimer = findViewById(R.id.recordTimer)
        recordLevel = findViewById(R.id.recordLevel)
        progress = findViewById(R.id.progress)
        onboardingView = findViewById(R.id.onboardingView)
        welcomeView = findViewById(R.id.welcomeView)
        welcomeScroll = findViewById(R.id.welcomeScroll)
        drawerLayout = findViewById(R.id.drawerLayout)
        drawerList = findViewById(R.id.drawerList)

        btnAttach.setOnClickListener { v ->
            PopupMenu(this, v).apply {
                menu.add(0, 1, 0, R.string.attach_image)
                menu.add(0, 2, 1, R.string.take_photo)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> pickImageLauncher.launch("image/*")
                        2 -> capturePhoto()
                    }
                    true
                }
            }.show()
        }
        btnRemoveImage.setOnClickListener { clearPendingImage() }
        btnMic.setOnClickListener { toggleVoice() }
        modelPill.setOnClickListener { askModel() }

        findViewById<TextView>(R.id.btnSidebar).setOnClickListener {
            drawerLayout.openDrawer(android.view.Gravity.START)
        }
        findViewById<Button>(R.id.btnNewChat).setOnClickListener {
            while (history.length() > 0) history.remove(0)
            bubbleContainer.removeAllViews()
            updateWelcome()
            drawerLayout.closeDrawer(android.view.Gravity.START)
        }
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                populateDrawer()
            }
        })

        for (chipId in listOf(R.id.chip1, R.id.chip2, R.id.chip3, R.id.chip4)) {
            findViewById<Button>(chipId).setOnClickListener { v ->
                input.setText((v as Button).text.toString())
                input.requestFocus()
            }
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        apiKey = prefs.getString(KEY_API, null)
        model = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        keyType = prefs.getString(KEY_TYPE, null)
        packageName = prefs.getString(KEY_PLAN_NAME, null)?.ifEmpty { null }
        isPaygKey = prefs.getBoolean(KEY_PAYG, false)
        try {
            val arr = JSONArray(prefs.getString(KEY_ALLOWED, "[]") ?: "[]")
            if (arr.length() > 0) {
                val l = mutableListOf<String>()
                for (i in 0 until arr.length()) l.add(arr.optString(i))
                allowedModels = l
            }
        } catch (_: Exception) {
        }

        findViewById<Button>(R.id.btnOnboardKey).setOnClickListener { askApiKey() }
        findViewById<Button>(R.id.btnOnboardWeb).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        val nameInput = findViewById<EditText>(R.id.nameInput)
        findViewById<Button>(R.id.btnLanjut).setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.name_missing, Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putString(KEY_NAME, name).apply()
                refreshOnboarding()
            }
        }

        btnSend.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sendMessage()
        }
        updateWelcome()
        assistant = try {
            JSONObject(prefs.getString(KEY_ASSISTANT, null) ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        if (apiKey.isNullOrEmpty()) {
            onboardingView.visibility = View.VISIBLE
            refreshOnboarding()
        } else {
            showKeyType(prefs.getString(KEY_TYPE, null))
        }
        loadDbPromptChips()
        loadModels()
        detectAccess()
        if (assistant.optString("prompt").isEmpty()) {
            applyPersona("Umum", getString(R.string.persona_general_prompt), silent = true)
        } else {
            loadPersonaChips()
        }
    }

    private fun updateWelcome() {
        val hasMessages = history.length() > 0 || bubbleContainer.childCount > 0
        welcomeScroll.visibility = if (hasMessages) View.GONE else View.VISIBLE
        val name = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_NAME, null)
        val g = greetingBase() + if (name.isNullOrEmpty()) "" else ", $name"
        findViewById<TextView>(R.id.txtHeaderGreeting).text = g
    }

    private fun loadSessions(): JSONArray {
        return try {
            JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_SESSIONS, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun saveCurrentSession() {
        if (history.length() == 0) return
        try {
            var title = ""
            for (i in 0 until history.length()) {
                val m = history.getJSONObject(i)
                if (m.optString("role") == "user") {
                    title = m.optString("content")
                    break
                }
            }
            title = title.replace("\n", " ").trim()
            if (title.length > 40) title = title.take(40) + "."
            if (title.isEmpty()) return
            val sessions = loadSessions()
            val entry = JSONObject().put("title", title)
                .put("ts", System.currentTimeMillis())
                .put("messages", history)
            var replaced = false
            for (i in 0 until sessions.length()) {
                if (sessions.getJSONObject(i).optString("title") == title) {
                    sessions.put(i, entry)
                    replaced = true
                    break
                }
            }
            if (!replaced) sessions.put(0, entry)
            val trimmed = JSONArray()
            val max = minOf(sessions.length(), 20)
            for (i in 0 until max) trimmed.put(sessions.getJSONObject(i))
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(KEY_SESSIONS, trimmed.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun populateDrawer() {
        drawerList.removeAllViews()
        val sessions = loadSessions()
        if (sessions.length() == 0) {
            val tv = TextView(this)
            tv.text = getString(R.string.empty_history_hint)
            tv.setTextColor(getColor(R.color.textSecondary))
            tv.textSize = 14f
            tv.setPadding(dp(24), dp(24), dp(24), dp(24))
            drawerList.addView(tv)
            return
        }
        for (i in 0 until sessions.length()) {
            val s = sessions.getJSONObject(i)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.background = getDrawable(R.drawable.bg_drawer_item_ripple)
            row.setPadding(dp(16), dp(12), dp(16), dp(12))
            row.isClickable = true

            val title = TextView(this)
            title.text = s.optString("title")
            title.setTextColor(getColor(R.color.textPrimary))
            title.textSize = 15f
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            row.addView(title)

            val time = TextView(this)
            time.text = formatTime(s.optLong("ts"))
            time.setTextColor(getColor(R.color.textSecondary))
            time.textSize = 11.5f
            row.addView(time)

            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            p.topMargin = dp(8)
            row.layoutParams = p
            row.setOnClickListener {
                openSession(s)
                drawerLayout.closeDrawer(Gravity.START)
            }
            drawerList.addView(row)
        }
    }

    private fun formatTime(ts: Long): String {
        if (ts <= 0) return ""
        return try {
            SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(ts))
        } catch (_: Exception) {
            ""
        }
    }

    private fun openSession(s: JSONObject) {
        val msgs = s.optJSONArray("messages") ?: return
        while (history.length() > 0) history.remove(0)
        bubbleContainer.removeAllViews()
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            history.put(m)
            addBubble(
                m.optString("content"),
                m.optString("role") == "user",
                m.optString("image").ifEmpty { null }
            )
        }
        updateWelcome()
    }

    private fun refreshOnboarding() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, null)
        findViewById<TextView>(R.id.txtGreeting).text = greetingBase() +
            if (name.isNullOrEmpty()) "" else ", $name"
        findViewById<EditText>(R.id.nameInput).visibility =
            if (name.isNullOrEmpty()) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnLanjut).visibility =
            if (name.isNullOrEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.txtSteps).visibility =
            if (name.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun greetingBase(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            h < 11 -> getString(R.string.greeting_morning)
            h < 15 -> getString(R.string.greeting_noon)
            h < 18 -> getString(R.string.greeting_afternoon)
            else -> getString(R.string.greeting_night)
        }
    }

    private fun autoDetectKey() {
        if (apiKey.isNullOrEmpty()) return
        Toast.makeText(this, R.string.key_check_running, Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val result = probeKey(apiKey!!)
            withContext(Dispatchers.Main) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_TYPE, result).apply()
                showKeyType(result)
            }
        }
    }

    private fun probeKey(key: String): String {
        var type = "invalid"
        try {
            val payload = JSONObject()
                .put("model", "mimo-v2.5")
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", "hai")))
                .put("max_tokens", 1)
            val conn = URL("$API_BASE/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            var body: String? = null
            if (code == 200) {
                type = "paket"
            } else {
                body = conn.errorStream?.bufferedReader()?.readText()
            }
            if (code != 200 && body != null) {
                val errObj = JSONObject(body).optJSONObject("error")
                val errCode = errObj?.optString("code")
                when {
                    code == 401 -> type = "invalid"
                    errCode == "model_requires_package" -> type = "payg"
                    code == 403 -> type = "payg"
                    code == 402 -> type = "paket"
                    code == 429 -> type = "paket"
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
        return type
    }

    private fun showKeyType(type: String?) {
        keyType = type
        val badge = when (type) {
            "paket" -> {
                Toast.makeText(this, R.string.key_type_paket, Toast.LENGTH_LONG).show()
                getString(R.string.key_badge_paket)
            }
            "payg" -> {
                Toast.makeText(this, R.string.key_type_payg, Toast.LENGTH_LONG).show()
                getString(R.string.key_badge_payg)
            }
            "invalid" -> {
                Toast.makeText(this, R.string.key_type_invalid, Toast.LENGTH_LONG).show()
                null
            }
            else -> null
        }
        val badgeView = findViewById<TextView>(R.id.txtKeyBadge)
        if (badge != null) {
            badgeView.text = badge
            badgeView.visibility = View.VISIBLE
        } else {
            badgeView.visibility = View.GONE
        }
        applyAssistantLabel()
        loadPersonaChips()
        loadModels()
        detectAccess()
    }

    private fun applyAssistantLabel() {
        var sub = getString(R.string.onboarding_tagline)
        val personaName = assistant.optString("sub")
        if (personaName.isNotEmpty()) {
            sub = personaName
        }
        findViewById<TextView>(R.id.txtHeaderBrand).text = sub
        findViewById<TextView>(R.id.txtPersona).text =
            if (personaName.isEmpty()) getString(R.string.persona_general)
            else "Partner: $personaName"
    }

    override fun onResume() {
        super.onResume()
        assistant = try {
            JSONObject(
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_ASSISTANT, null) ?: "{}"
            )
        } catch (_: Exception) {
            JSONObject()
        }
        applyAssistantLabel()
    }

    private fun askApiKey() {
        val view = layoutInflater.inflate(R.layout.dialog_api_key, null)
        val edit = view.findViewById<EditText>(R.id.apiKeyInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.api_key_title)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.save) { _, _ ->
                apiKey = edit.text.toString().trim()
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_API, apiKey).apply()
                onboardingView.visibility = View.GONE
                autoDetectKey()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun askModel() {
        progress.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val fresh = fetchModels()
            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
                if (fresh.isNotEmpty()) {
                    modelsCache = fresh
                    updateModelPill()
                }
                showModelPicker()
            }
        }
    }

    private fun showModelPicker() {
        val list = modelsCache
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.models_failed, Toast.LENGTH_LONG).show()
            return
        }
        val ordered = list.filter { showAllModels || isModelAllowed(it.id) } +
            list.filter { !(showAllModels || isModelAllowed(it.id)) }

        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(10), dp(16), dp(10))

        val info = TextView(this)
        info.text = accessSummary()
        info.setTextColor(getColor(R.color.textSecondary))
        info.textSize = 12.5f
        info.setLineSpacing(dp(3).toFloat(), 1f)
        box.addView(info)

        val toggle = android.widget.CheckBox(this)
        toggle.text = getString(R.string.model_show_all)
        toggle.isChecked = showAllModels
        toggle.setOnCheckedChangeListener { _, checked ->
            showAllModels = checked
            val all = modelsCache
            val ord = all.filter { showAllModels || isModelAllowed(it.id) } +
                all.filter { !(showAllModels || isModelAllowed(it.id)) }
            refreshModelRows(box, ord)
        }
        box.addView(toggle)

        refreshModelRows(box, ordered)
        scroll.addView(box)

        AlertDialog.Builder(this)
            .setTitle(R.string.choose_model)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshModelRows(box: LinearLayout, list: List<ModelItem>) {
        box.removeViews(2, box.childCount - 2)
        for (m in list) {
            val allowed = showAllModels || isModelAllowed(m.id)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.background = getDrawable(R.drawable.bg_drawer_item_ripple)
            row.setPadding(dp(16), dp(12), dp(16), dp(12))
            row.isClickable = true

            val title = TextView(this)
            title.text = if (allowed) m.name else "${m.name}  ·  ${getString(R.string.outside_package)}"
            title.setTextColor(
                getColor(if (allowed) R.color.textPrimary else R.color.textSecondary)
            )
            title.textSize = 15f
            row.addView(title)

            val sub = TextView(this)
            sub.text = m.id
            sub.setTextColor(getColor(R.color.textSecondary))
            sub.textSize = 11.5f
            sub.setPadding(0, dp(3), 0, 0)
            row.addView(sub)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            row.layoutParams = lp

            row.setOnClickListener {
                if (allowed) {
                    switchModel(m.id)
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.model_blocked_msg, m.id, packageName ?: "paket"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            box.addView(row)
        }
    }

    private fun accessSummary(): String {
        return when {
            isPaygKey -> getString(R.string.access_payg)
            !packageName.isNullOrEmpty() ->
                getString(R.string.access_package, packageName!!, allowedModels?.size ?: 0)
            else -> getString(R.string.access_unknown)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addBubble(text: String, isUser: Boolean, imagePath: String? = null): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.background = getDrawable(
            if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_ai
        )
        tv.setTextColor(getColor(if (isUser) R.color.textOnBrand else R.color.textPrimary))
        tv.textSize = 15f
        tv.setLineSpacing(4f * resources.displayMetrics.density, 1f)
        val pad = resources.getDimensionPixelSize(R.dimen.bubble_padding)
        tv.setPadding(pad, pad, pad, pad)
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.76).toInt()
        tv.setOnLongClickListener {
            copyToClipboard(tv.text.toString())
            true
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = if (isUser) Gravity.END else Gravity.TOP

        if (!isUser) {
            val avatar = ImageView(this)
            avatar.setImageResource(R.drawable.ic_brand_mark)
            avatar.background = getDrawable(R.drawable.bg_logo_circle)
            avatar.setPadding(6, 6, 6, 6)
            avatar.contentDescription = getString(R.string.brand_small)
            val avParams = LinearLayout.LayoutParams(dp(30), dp(30))
            avParams.marginEnd = dp(8)
            avatar.layoutParams = avParams
            avatar.setOnClickListener { copyToClipboard(tv.text.toString()) }
            row.addView(avatar)

            val column = LinearLayout(this)
            column.orientation = LinearLayout.VERTICAL
            val name = TextView(this)
            name.text = getString(R.string.brand_small)
            name.textSize = 10.5f
            name.setTextColor(getColor(R.color.textSecondary))
            name.setPadding(dp(2), 0, 0, dp(4))
            column.addView(name)
            column.addView(tv)
            row.addView(column)
        } else {
            val column = LinearLayout(this)
            column.orientation = LinearLayout.VERTICAL
            column.gravity = Gravity.END
            if (imagePath != null) {
                val img = ImageView(this)
                img.setImageBitmap(BitmapFactory.decodeFile(imagePath))
                img.background = getDrawable(R.drawable.bg_preview)
                img.clipToOutline = true
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.contentDescription = getString(R.string.image_bubble_desc)
                val ip = LinearLayout.LayoutParams(dp(190), dp(190))
                ip.bottomMargin = dp(6)
                img.layoutParams = ip
                column.addView(img)
            }
            column.addView(tv)
            row.addView(column)
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = resources.getDimensionPixelSize(R.dimen.bubble_margin)
        row.layoutParams = params
        bubbleContainer.addView(row)
        scrollToBottom()
        return tv
    }

    private fun loadPersonaChips() {
        val box = findViewById<LinearLayout>(R.id.personaChips) ?: return
        box.removeAllViews()

        val personas = mutableListOf<Triple<String, String, String>>()
        personas.add(
            Triple(
                "Umum",
                "🌐",
                getString(R.string.persona_general_prompt)
            )
        )
        try {
            val body = assets.open("assistants.json").bufferedReader().readText()
            val root = JSONObject(body)
            val cats = root.optJSONArray("kategori") ?: JSONArray()
            for (i in 0 until cats.length()) {
                val subs = cats.getJSONObject(i).optJSONArray("sub") ?: JSONArray()
                for (j in 0 until subs.length()) {
                    val sub = subs.getJSONObject(j)
                    val name = sub.optString("nama")
                    if (name.isBlank()) continue
                    personas.add(
                        Triple(
                            name,
                            sub.optString("emoji"),
                            sub.optString("prompt")
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        val activeName = assistant.optString("sub")
        val preferred = listOf(
            "Umum", "Owner Bisnis", "Marketing & Seller", "Keuangan Usaha",
            "Konten Kreator", "Guru & Pengajar", "Belajar & Ujian",
            "Teman Curhat", "Akuntan & Manajer Keuangan", "Dokter Umum",
            "Mekanik Mobil", "Instalasi Listrik Rumah", "Bahasa Inggris & Lainnya"
        )
        val ordered = mutableListOf<Triple<String, String, String>>()
        for (name in preferred) {
            val found = personas.firstOrNull { it.first == name }
            if (found != null) ordered.add(found)
        }
        for (p in personas) {
            if (ordered.none { it.first == p.first }) ordered.add(p)
        }

        for (p in ordered.take(14)) {
            val tv = TextView(this)
            tv.text = if (p.second.isNotEmpty()) "${p.second} ${p.first}" else p.first
            tv.textSize = 13f
            val isActive = p.first == activeName ||
                (activeName.isEmpty() && p.first == "Umum")
            tv.setTextColor(
                getColor(if (isActive) R.color.primaryDeep else R.color.textSecondary)
            )
            tv.setPadding(dp(16), dp(10), dp(16), dp(10))
            tv.background = getDrawable(
                if (isActive) R.drawable.bg_pill_active else R.drawable.bg_chip
            )
            tv.setOnClickListener { applyPersona(p.first, p.third) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            tv.layoutParams = lp
            box.addView(tv)
        }
    }

    private fun applyPersona(name: String, prompt: String, silent: Boolean = false) {
        val persona = JSONObject()
            .put("sub", name)
            .put("emoji", "")
            .put("prompt", prompt)
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putString(KEY_ASSISTANT, persona.toString()).apply()
        assistant = persona
        applyAssistantLabel()
        loadPersonaChips()
        if (!silent) {
            Toast.makeText(this, getString(R.string.toast_persona, name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------- GAMBAR ----------------

    private fun capturePhoto() {
        try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
            cameraUri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            takePictureLauncher.launch(cameraUri)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.image_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImageUri(uri: Uri) {
        try {
            val bitmap = decodeScaled(uri, 1280) ?: run {
                Toast.makeText(this, R.string.image_failed, Toast.LENGTH_SHORT).show()
                return
            }
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val file = File(dir, "img_${UUID.randomUUID()}.jpg")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out)
            }
            pendingImagePath = file.absolutePath
            previewImage.setImageBitmap(bitmap)
            previewImage.clipToOutline = true
            attachPreview.visibility = View.VISIBLE
            Toast.makeText(this, R.string.image_added, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.image_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun decodeScaled(uri: Uri, maxPx: Int): Bitmap? {
        try {
            val resolver = contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            while (largest / sample > maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            return scaleToMax(bmp, maxPx)
        } catch (_: Exception) {
            return null
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

    private fun clearPendingImage() {
        pendingImagePath = null
        attachPreview.visibility = View.GONE
        previewImage.setImageDrawable(null)
    }

    private fun imageDataUri(path: String?): String? {
        if (path.isNullOrEmpty()) return null
        return try {
            val bytes = File(path).readBytes()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- SUARA (rekam + ASR server) ----------------

    private fun toggleVoice() {
        if (isRecording) {
            stopRecording()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        startRecording()
    }

    private fun startRecording() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                Toast.makeText(this, R.string.voice_not_available, Toast.LENGTH_SHORT).show()
                return
            }
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
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
            recordStartedAt = System.currentTimeMillis()
            rec.startRecording()

            recordBar.visibility = View.VISIBLE
            btnMic.background = getDrawable(R.drawable.bg_mic_active)

            recordTimerHandler = android.os.Handler(mainLooper)
            val timer = recordTimerHandler!!
            timer.post(object : Runnable {
                override fun run() {
                    if (!isRecording) return
                    val sec = (System.currentTimeMillis() - recordStartedAt) / 1000
                    recordTimer.text = String.format(
                        Locale.getDefault(), "Merekam %d:%02d", sec / 60, sec % 60
                    )
                    timer.postDelayed(this, 500)
                }
            })

            recordThread = Thread {
                val buf = ByteArray(minBuf)
                while (isRecording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        recordBuffer?.write(buf, 0, n)
                        var peak = 0
                        var i = 0
                        while (i + 1 < n) {
                            val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
                            val a = Math.abs(v.toInt())
                            if (a > peak) peak = a
                            i += 2
                        }
                        val level = (peak / 32768f * 100).toInt().coerceIn(0, 100)
                        runOnUiThread { recordLevel.progress = level }
                    }
                }
            }.also { it.start() }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.voice_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
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
        recordBar.visibility = View.GONE
        btnMic.background = getDrawable(R.drawable.bg_icon_button)
        recordTimerHandler?.removeCallbacksAndMessages(null)

        val pcm = recordBuffer?.toByteArray() ?: return
        recordBuffer = null
        if (pcm.size < SAMPLE_RATE) {
            Toast.makeText(this, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        transcribeAudio(pcm)
    }

    private fun transcribeAudio(pcm: ByteArray) {
        Toast.makeText(this, R.string.voice_transcribing, Toast.LENGTH_SHORT).show()
        val wav = wrapWav(pcm)
        CoroutineScope(Dispatchers.IO).launch {
            var text: String? = null
            var failed = false
            try {
                val payload = JSONObject()
                    .put("model", ASR_MODEL)
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
                val conn = URL("$API_BASE/chat/completions").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    text = JSONObject(body)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        ?.trim()
                } else {
                    failed = true
                }
                conn.disconnect()
            } catch (_: Exception) {
                failed = true
            }
            withContext(Dispatchers.Main) {
                if (!text.isNullOrEmpty()) {
                    input.setText(text)
                    sendMessage()
                } else if (failed && SpeechRecognizer.isRecognitionAvailable(this@NativeChatActivity)) {
                    Toast.makeText(this@NativeChatActivity, R.string.voice_asr_error, Toast.LENGTH_SHORT).show()
                    startLocalDictation()
                } else {
                    Toast.makeText(this@NativeChatActivity, R.string.voice_asr_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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

    private fun startLocalDictation() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.voice_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speech = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { stopLocalDictation() }
            override fun onError(error: Int) { stopLocalDictation() }
            override fun onResults(results: Bundle?) {
                stopLocalDictation()
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().trim()
                if (text.isNotEmpty()) {
                    input.setText(text)
                    sendMessage()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
    }

    private fun stopLocalDictation() {
        try {
            speech?.stopListening()
            speech?.destroy()
        } catch (_: Exception) {
        }
        speech = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleVoice()
            } else {
                Toast.makeText(this, R.string.voice_permission, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- MODEL ----------------

    // ---------------- AKSES MODEL / PAKET ----------------

    private fun loadModels() {
        if (apiKey.isNullOrEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            val list = fetchModels()
            if (list.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                modelsCache = list
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                if (!prefs.getBoolean(KEY_MODEL_SET, false)) {
                    val allowedFirst = list.firstOrNull { isModelAllowed(it.id) }
                    val first = allowedFirst ?: list.firstOrNull()
                    if (first != null) {
                        model = first.id
                        prefs.edit().putString(KEY_MODEL, model).apply()
                    }
                }
                updateModelPill()
                applyPlanRestriction()
            }
        }
    }

    /** Deteksi paket dari akun (server) → daftar model yang ditanggung paket. */
    private fun detectAccess() {
        if (apiKey.isNullOrEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            val status = fetchAccountStatus()
            var plan: String? = null
            var detectedKey: String? = null
            var allowed: List<String>? = null
            var payg = false
            if (status != null) {
                if (!status.hasPackage) {
                    payg = true
                    plan = status.name
                } else {
                    plan = status.name
                    val pk = packageFromText(status.name ?: "")
                    if (pk != null && PACKAGE_MODELS.containsKey(pk)) {
                        allowed = PACKAGE_MODELS[pk]
                        detectedKey = pk
                    }
                }
            }
            if (plan == null) {
                val pk = packageFromKey(apiKey ?: "")
                if (pk != null) {
                    plan = PACKAGE_NAMES[pk]
                    allowed = PACKAGE_MODELS[pk]
                    detectedKey = pk
                }
            }
            withContext(Dispatchers.Main) {
                packageName = plan
                packageKey = detectedKey
                allowedModels = allowed
                isPaygKey = payg
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val editor = prefs.edit()
                editor.putString(KEY_PLAN_NAME, plan ?: "")
                editor.putBoolean(KEY_PAYG, payg)
                editor.putLong(KEY_ACCESS_TS, System.currentTimeMillis())
                if (allowed != null) {
                    editor.putString(KEY_ALLOWED, JSONArray(allowed).toString())
                } else {
                    editor.remove(KEY_ALLOWED)
                }
                editor.apply()
                updateKeyBadge()
                applyPlanRestriction()
            }
        }
    }

    private fun fetchAccountStatus(): AccountStatus? {
        return try {
            val conn = URL("$API_BASE/me/status").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val o = JSONObject(body)
            AccountStatus(
                hasPackage = o.optBoolean("ada_paket", false),
                name = o.optString("nama_paket").ifEmpty { null },
                credit = o.optDouble("saldo_kredit", 0.0),
                until = o.optString("paket_sampai").ifEmpty { null }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun packageFromKey(key: String): String? {
        val k = key.lowercase()
        return when {
            k.startsWith("sk-hemat") -> "hemat"
            k.startsWith("sk-coding") -> "coding"
            k.startsWith("sk-minimax") -> "minimax"
            k.startsWith("sk-pro") -> "pro"
            k.startsWith("sk-claude") -> "claude_regular"
            k.startsWith("sk-asian") -> "asian_starter"
            k.startsWith("sk-longcat") -> "longcat"
            else -> null
        }
    }

    private fun packageFromText(text: String): String? {
        val t = text.lowercase()
        return when {
            t.contains("bisnis") -> "bisnis"
            t.contains("hemat") -> "hemat"
            t.contains("coding") -> "coding"
            t.contains("minimax") -> "minimax"
            t.contains("longcat") -> "longcat"
            t.contains("asian") -> if (t.contains("coder")) "asian_coder" else "asian_starter"
            t.contains("claude") -> when {
                t.contains("starter") -> "claude_starter"
                t.contains("dev") -> "claude_dev"
                t.contains("studio") -> "claude_studio"
                t.contains("pro") -> "claude_pro"
                else -> "claude_regular"
            }
            t.contains("pro") -> "pro"
            else -> null
        }
    }

    private fun isModelAllowed(id: String): Boolean {
        val list = allowedModels ?: return true
        if (list.isEmpty()) return true
        val m = id.lowercase()
        if (list.any { e ->
                val entry = e.lowercase()
                m == entry || m.contains(entry) || entry.contains(m)
            }
        ) return true
        // Model baru dalam keluarga paket yang sama (mis. LongCat-2.5 GA vs LongCat-2.5-Preview)
        // otomatis dianggap bagian paket begitu tersedia di server.
        val token = packageToken ?: return false
        return m.startsWith("$token-") || m.startsWith("$token.") ||
            m.contains("/$token-") || m.contains("/$token.")
    }

    /** Token nama model yang menandai "keluarga" paket (LongCat, Claude, ...). */
    private val packageToken: String?
        get() {
            val pk = packageKey ?: return null
            return when (pk) {
                "longcat" -> "longcat"
                "claude_starter", "claude_regular", "claude_dev", "claude_studio", "claude_pro" -> "claude"
                "asian_starter" -> "deepseek"
                "asian_coder" -> null
                else -> null
            }
        }

    private fun applyPlanRestriction() {
        if (allowedModels == null || showAllModels) return
        if (isModelAllowed(model)) return
        val fallback = modelsCache.firstOrNull { isModelAllowed(it.id) }
        if (fallback != null && fallback.id != model) {
            switchModel(fallback.id, silent = true)
        }
    }

    private fun updateKeyBadge() {
        val badge = findViewById<TextView>(R.id.txtKeyBadge)
        val label = when {
            isPaygKey -> getString(R.string.key_badge_payg)
            !packageName.isNullOrEmpty() -> packageName!!
            else -> getString(R.string.key_badge_paket)
        }
        badge.text = label
        badge.visibility = View.VISIBLE
    }

    private fun updateModelPill() {
        val label = modelsCache.firstOrNull { it.id == model }?.name ?: model
        modelPill.text = label
    }

    private fun switchModel(m: String, silent: Boolean = false) {
        model = m
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_MODEL, m)
            .putBoolean(KEY_MODEL_SET, true)
            .apply()
        updateModelPill()
        if (!silent) {
            Toast.makeText(this, getString(R.string.model_switched, m), Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchModels(): List<ModelItem> {
        return try {
            val conn = URL("$API_BASE/models").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val out = mutableListOf<ModelItem>()
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val o = data.getJSONObject(i)
                    val id = o.optString("id")
                    if (id.isBlank()) continue
                    out.add(
                        ModelItem(
                            id = id,
                            name = o.optString("name").ifEmpty { id },
                            inPlan = parsePlanFlag(o)
                        )
                    )
                }
            }
            conn.disconnect()
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Baca penanda akses paket dari server (opsional, jika backend mengirimkannya). */
    private fun parsePlanFlag(o: JSONObject): Boolean? {
        val truthy = listOf(
            "in_plan", "in_package", "plan_only", "package_only",
            "included_in_plan", "plan_included", "is_plan_model"
        )
        for (k in truthy) {
            if (o.has(k)) {
                val v = o.opt(k)
                if (v is Boolean) return v
                if (v is Number) return v.toInt() != 0
            }
        }
        val access = o.optString("access").lowercase()
        if (access == "paket" || access == "package" || access == "plan") return true
        if (access == "payg") return false
        val tags = o.optJSONArray("tags")
        if (tags != null) {
            for (i in 0 until tags.length()) {
                val t = tags.optString(i).lowercase()
                if (t == "paket" || t == "package" || t == "plan") return true
                if (t == "payg") return false
            }
        }
        return null
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.rootContent)
        val header = findViewById<View>(R.id.headerBar)
        val headerPadTop = header.paddingTop
        val rootPadBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.setPadding(
                header.paddingLeft,
                headerPadTop + bars.top,
                header.paddingRight,
                header.paddingBottom
            )
            root.setPadding(
                root.paddingLeft,
                root.paddingTop,
                root.paddingRight,
                rootPadBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.smoothScrollTo(0, bubbleContainer.height) }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.brand_small), text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        val imagePath = pendingImagePath
        if (text.isEmpty() && imagePath == null) return
        if (apiKey.isNullOrEmpty()) {
            askApiKey()
            return
        }
        if (!showAllModels && !isModelAllowed(model)) {
            Toast.makeText(
                this,
                getString(R.string.model_blocked_msg, model, packageName ?: "paket Anda"),
                Toast.LENGTH_LONG
            ).show()
            showModelPicker()
            return
        }
        input.setText("")
        clearPendingImage()
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(input.windowToken, 0)

        val caption = if (text.isEmpty()) "(lihat gambar)" else text
        addBubble(caption, isUser = true, imagePath = imagePath)
        val userMsg = JSONObject().put("role", "user").put("content", text)
        if (imagePath != null) userMsg.put("image", imagePath)
        history.put(userMsg)
        updateWelcome()
        val aiBubble = addBubble(getString(R.string.thinking), isUser = false)
        typingAnim = ObjectAnimator.ofFloat(aiBubble, "alpha", 0.35f, 1f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        btnSend.isEnabled = false
        btnSend.alpha = 0.45f
        progress.visibility = View.VISIBLE

        sendJob = CoroutineScope(Dispatchers.IO).launch {
            var error: String? = null
            val sb = StringBuilder()
            try {
                val msgs = JSONArray()
                val ass = assistant.optString("prompt")
                var systemText = ass
                val webSearchInstruction = "Jika pertanyaan memerlukan info terbaru, berita, atau data terkini, gunakan pencarian web sebelum menjawab."
                if (systemText.isEmpty()) systemText = webSearchInstruction
                if (systemText.isNotEmpty() && !systemText.contains("pencarian web", ignoreCase = true)) {
                    systemText = systemText + "\n\n" + webSearchInstruction
                }
                if (systemText.isNotEmpty()) msgs.put(JSONObject().put("role", "system").put("content", systemText))
                for (i in 0 until history.length()) {
                    val m = history.getJSONObject(i)
                    val imgPath = m.optString("image")
                    if (imgPath.isNotEmpty()) {
                        val dataUri = imageDataUri(imgPath)
                        if (dataUri != null) {
                            val parts = JSONArray()
                            val captionText = m.optString("content")
                            if (captionText.isNotEmpty()) {
                                parts.put(
                                    JSONObject().put("type", "text").put("text", captionText)
                                )
                            }
                            parts.put(
                                JSONObject().put("type", "image_url").put(
                                    "image_url", JSONObject().put("url", dataUri)
                                )
                            )
                            msgs.put(
                                JSONObject().put("role", "user").put("content", parts)
                            )
                            continue
                        }
                    }
                    msgs.put(JSONObject().put("role", m.optString("role")).put("content", m.optString("content")))
                }

                val webSearchParams = JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("query", JSONObject().put("type", "string").put("description", "Kata kunci pencarian berita/informasi terbaru.")))
                    .put("required", JSONArray().put("query"))
                val webTools = JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", "web_search")
                        .put("description", "Mencari informasi terbaru dari internet berdasarkan kata kunci yang diberikan.")
                        .put("parameters", webSearchParams))
                var payload = JSONObject()
                    .put("model", model)
                    .put("messages", msgs)
                    .put("stream", true)
                    .put("tools", webTools)
                    .put("tool_choice", "auto")

                var content: String? = null
                var lastToolCalls: JSONArray? = null
                var rounds = 0
                while (content == null && rounds < 3) {
                    sb.setLength(0)
                    rounds = rounds + 1
                    val conn = URL("$API_BASE/chat/completions").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 20000
                    conn.readTimeout = 120000
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                    if (conn.responseCode != 200) {
                        val err = conn.errorStream?.bufferedReader()?.readText()
                        error = parseError(err, conn.responseCode)
                    } else {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (!line!!.startsWith("data:")) continue
                            val data = line!!.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            val deltaObj = JSONObject(data)
                                .optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                            val delta = deltaObj?.optString("content")
                            if (!delta.isNullOrEmpty()) {
                                sb.append(delta)
                                withContext(Dispatchers.Main) {
                                    typingAnim?.cancel()
                                    typingAnim = null
                                    aiBubble.alpha = 1f
                                    aiBubble.text = sb.toString()
                                    scrollToBottom()
                                }
                            }
                            val deltaTools = deltaObj?.optJSONArray("tool_calls")
                            if (deltaTools != null) lastToolCalls = deltaTools
                        }
                        content = sb.toString()
                    }
                    conn.disconnect()
                    if (content != null) {
                        if (lastToolCalls != null && rounds < 3) {
                            val assistantMsg = JSONObject()
                                .put("role", "assistant")
                                .put("content", JSONObject.NULL)
                                .put("tool_calls", lastToolCalls)
                            val toolMsg = JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", lastToolCalls.optJSONObject(0)?.optString("id") ?: "")
                                .put("content", "Hasil pencarian web sedang diproses, mohon hasilkan jawaban final berdasarkan informasi terbaru.")
                            msgs.put(msgs.length(), assistantMsg)
                            msgs.put(msgs.length(), toolMsg)
                            payload = JSONObject()
                                .put("model", model)
                                .put("messages", msgs)
                                .put("stream", true)
                                .put("tools", webTools)
                                .put("tool_choice", "auto")
                            content = null
                            lastToolCalls = null
                            continue
                        }
                        break
                    }
                    if (rounds == 1 && error != null && Regex("(?i)tool|function|model_no_tool").containsMatchIn(error)) {
                        payload = JSONObject()
                            .put("model", model)
                            .put("messages", msgs)
                            .put("stream", true)
                        continue
                    }
                    break
                }
            } catch (e: Exception) {
                if (sb.isEmpty()) error = e.message ?: getString(R.string.error_generic)
            }

            withContext(Dispatchers.Main) {
                typingAnim?.cancel()
                typingAnim = null
                aiBubble.alpha = 1f
                progress.visibility = View.GONE
                btnSend.isEnabled = true
                btnSend.alpha = 1f
                if (error != null) {
                    aiBubble.text = getString(R.string.error_prefix, error)
                    aiBubble.setTextColor(getColor(R.color.errorText))
                    aiBubble.background = getDrawable(R.drawable.bg_bubble_error)
                    if (lastErrorCode == "model_requires_package" ||
                        lastErrorCode == "model_not_billable" ||
                        lastErrorCode == "insufficient_credits"
                    ) {
                        showModelRestrictionDialog()
                    }
                    for (i in history.length() - 1 downTo 0) {
                        if (history.getJSONObject(i) == userMsg) history.remove(i)
                    }
                    updateWelcome()
                } else if (sb.isNotEmpty()) {
                    history.put(
                        JSONObject().put("role", "assistant").put("content", sb.toString())
                    )
                    saveCurrentSession()
                    showPromptAdvice(text)
                } else {
                    aiBubble.text = getString(R.string.error_empty)
                }
            }
        }
    }

    private fun promptTitle(item: JSONObject): String {
        val t = item.optString("judul")
        if (t.isNotEmpty()) return t
        return item.optString("nama")
    }

    private fun promptContent(item: JSONObject): String {
        val c = item.optString("konten")
        if (c.isNotEmpty()) return c
        return item.optString("prompt")
    }

    private fun fetchPromptSuggestion(query: String): String? {
        val items = fetchDbPrompts("/prompts/search?q=" +
            java.net.URLEncoder.encode(query, "UTF-8") + "&limit=2")
        if (items.isEmpty()) return null
        return items.joinToString(" | ") {
            val judul = promptTitle(it)
            val konten = promptContent(it)
            if (judul.isNotEmpty()) "[$judul] $konten" else konten
        }
    }

    private fun fetchDbPrompts(path: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        try {
            val conn = URL(API_BASE + path).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val root = JSONObject(body)
                val data = root.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) out.add(data.getJSONObject(i))
                } else {
                    val cats = root.optJSONArray("kategori") ?: JSONArray()
                    for (i in 0 until cats.length()) {
                        val subs = cats.getJSONObject(i).optJSONArray("sub") ?: JSONArray()
                        for (j in 0 until subs.length()) out.add(subs.getJSONObject(j))
                    }
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
        return out
    }

    private fun loadDbPromptChips() {
        CoroutineScope(Dispatchers.IO).launch {
            val items = fetchDbPrompts("/prompts?limit=12")
            if (items.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                val box = findViewById<LinearLayout>(R.id.promptSuggestions)
                if (box == null) return@withContext
                box.removeAllViews()
                val label = TextView(this@NativeChatActivity)
                label.text = getString(R.string.prompt_from_db)
                label.setTextColor(getColor(R.color.textSecondary))
                label.textSize = 11.5f
                label.letterSpacing = 0.12f
                label.setPadding(dp(4), dp(0), dp(4), dp(8))
                box.addView(label)
                for (item in items.take(4)) {
                    val tv = TextView(this@NativeChatActivity)
                    tv.text = promptTitle(item)
                    tv.setTextColor(getColor(R.color.textPrimary))
                    tv.textSize = 14f
                    tv.setPadding(dp(16), dp(14), dp(16), dp(14))
                    tv.background = getDrawable(R.drawable.bg_chip)
                    tv.setOnClickListener {
                        input.setText(promptContent(item))
                        input.requestFocus()
                    }
                    val p = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    p.topMargin = dp(8)
                    tv.layoutParams = p
                    box.addView(tv)
                }
            }
        }
    }

    private fun showPromptAdvice(userText: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val items = fetchPromptSuggestionItems(userText)
            if (items.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                for (item in items.take(2)) {
                    val tv = addBubble(
                        getString(R.string.prompt_advice_prefix) + " " + promptTitle(item),
                        isUser = false
                    )
                    tv.setOnClickListener {
                        input.setText(promptContent(item))
                        input.requestFocus()
                    }
                }
            }
        }
    }

    private fun fetchPromptSuggestionItems(query: String): List<JSONObject> {
        return fetchDbPrompts("/prompts/search?q=" +
            java.net.URLEncoder.encode(query, "UTF-8") + "&limit=3")
    }

    private fun parseError(body: String?, code: Int): String {
        try {
            if (!body.isNullOrEmpty()) {
                val errObj = JSONObject(body).optJSONObject("error")
                lastErrorCode = errObj?.optString("code")
                val msg = errObj?.optString("message")
                if (!msg.isNullOrEmpty()) return msg
            }
        } catch (_: Exception) {
        }
        return "HTTP $code"
    }

    private fun showModelRestrictionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.model_restricted_title)
            .setMessage(R.string.model_restricted_msg)
            .setPositiveButton(R.string.change_model) { _, _ -> askModel() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleMenuItem(id: Int): Boolean = when (id) {
        R.id.action_pick_assistant -> {
            startActivity(Intent(this, AssistantActivity::class.java))
            true
        }
        R.id.action_pick_prompt -> {
            startActivity(Intent(this, PromptActivity::class.java))
            true
        }
        R.id.action_change_model -> {
            askModel()
            true
        }
        R.id.action_change_key -> {
            askApiKey()
            true
        }
        R.id.action_clear_chat -> {
            while (history.length() > 0) history.remove(0)
            bubbleContainer.removeAllViews()
            updateWelcome()
            true
        }
        else -> false
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        sendJob?.cancel()
        typingAnim?.cancel()
        isRecording = false
        try {
            recorder?.stop()
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        recordTimerHandler?.removeCallbacksAndMessages(null)
        stopLocalDictation()
        super.onDestroy()
    }
}




