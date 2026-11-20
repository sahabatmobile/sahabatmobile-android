package com.sahabatmobile.chat

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.text.Spanned
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
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
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Calendar
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
        "longcat" to listOf("LongCat-2.5", "LongCat-2.5-Preview", "LongCat-2.0")
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
        const val KEY_ADVICE_SHOWN = "advice_shown"
        const val KEY_WIDE = "wide_mode"
        const val KEY_PAYG = "payg_key"
        const val KEY_SHOW_ALL = "show_all_models"
        const val KEY_WA_AUTO = "wa_auto_reply"
        const val REQ_AUDIO = 1001
        const val REQ_LOCATION = 1002
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
    private lateinit var headerBar: View
    private lateinit var floatControls: View
    private var headerVisible = true
    private var topInset = 0
    private var headerBasePadTop = 0
    private var rootBasePadTop = 0

    private val history = JSONArray()
    private var sendJob: Job? = null
    private var typingAnim: ObjectAnimator? = null
    private var apiKey: String? = null
    private var model: String = DEFAULT_MODEL
    private var assistant: JSONObject = JSONObject()
    private var pendingImagePath: String? = null

    // Fitur #6: mode pemindaian gambar (kalori/nota/produk/teks)
    private var pendingScanMode: String? = null
    private var cameraUri: Uri? = null
    private var speech: SpeechRecognizer? = null

    // Fitur #5: TextToSpeech — balasan AI dibacakan dengan suara
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false

    // Fitur #12: Susun Hari Saya (AI planner)
    private var plannerContext: String? = null
    private var pendingPlanParse = false
    private var pendingArticleTitle = ""
    private var pendingArticleLink = ""
    private var inputBaseHeight = 0
    private var inputFocused = false
    private val sliderHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sliderRunnable: Runnable? = null
    private var sliderEnabled = false
    private var wideMode: Boolean = false
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
        // Logging crash sementara untuk troubleshooting "app close saat buka pengaturan"
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("SM_CRASH", "UNCAUGHT on ${thread.name}", throwable)
            prevHandler?.uncaughtException(thread, throwable)
        }
        setContentView(R.layout.activity_native_chat)

        wideMode = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_WIDE, false)
        applyWindowInsets()

        // Lokasi dipakai tool nearby_places untuk mencari tempat terdekat.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_LOCATION
            )
        }

        // Jadwalkan ringkasan harian setiap app dibuka (agar alarm selalu fresh)
        DailySummaryReceiver.schedule(this)
        WeeklySummaryReceiver.schedule(this)
        // Fitur #3: jadwalkan ulang semua pesan terjadwal WA
        ScheduledMessageReceiver.scheduleAll(this)
        // Fitur #8: refresh widget home screen
        AssistantWidgetProvider.updateAll(this)

        findViewById<ImageView>(R.id.btnMenu).setOnClickListener { v ->
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

        // Fitur #5: init TextToSpeech
        tts = android.speech.tts.TextToSpeech(this) { status ->
            ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS
            if (ttsReady) {
                val result = tts?.setLanguage(java.util.Locale("id", "ID"))
                if (result == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                    result == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.setLanguage(java.util.Locale.getDefault())
                }
            }
        }
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
        headerBar = findViewById(R.id.headerBar)
        floatControls = findViewById(R.id.topbarFloatControls)

        // Topbar disembunyikan secara default; muncul saat tombol ▼ ditekan.
        headerBar.setOnClickListener { hideTopbar() }
        findViewById<TextView>(R.id.btnFloatSidebar).setOnClickListener {
            drawerLayout.openDrawer(Gravity.START)
        }
        findViewById<TextView>(R.id.btnFloatShowTopbar).setOnClickListener { showTopbar() }
        headerBar.post { hideTopbar(animate = false) }

        // Topbar otomatis muncul saat scroll ke atas, sembunyi saat scroll ke bawah
        attachAutoTopbar(scrollView)
        attachAutoTopbar(welcomeScroll)

        // Mulai mengetik → topbar sembunyi. Tinggi input menyesuaikan jumlah
        // baris teks dan mengecil sendiri saat teks dihapus / dikirim.
        input.setOnFocusChangeListener { _, hasFocus ->
            inputFocused = hasFocus
            if (hasFocus) hideTopbar()
            resizeInputToText()
        }
        input.post {
            if (inputBaseHeight <= 0) inputBaseHeight = input.height
            resizeInputToText()
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { resizeInputToText() }
        })

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
        modelPill.setOnClickListener { openSettingsTab() }

        findViewById<TextView>(R.id.btnSidebar).setOnClickListener {
            drawerLayout.openDrawer(android.view.Gravity.START)
        }
        findViewById<Button>(R.id.btnNewChat).setOnClickListener {
            while (history.length() > 0) history.remove(0)
            bubbleContainer.removeAllViews()
            updateWelcome()
            drawerLayout.closeDrawer(android.view.Gravity.START)
        }
        findViewById<Button>(R.id.btnDrawerProfile).setOnClickListener {
            drawerLayout.closeDrawer(Gravity.START)
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        findViewById<Button>(R.id.btnDrawerPapan).setOnClickListener {
            drawerLayout.closeDrawer(Gravity.START)
            startActivity(Intent(this, PapanActivity::class.java))
        }
        findViewById<Button>(R.id.btnDrawerTasks).setOnClickListener {
            drawerLayout.closeDrawer(Gravity.START)
            startActivity(
                Intent(this, ProfileActivity::class.java)
                    .putExtra(ProfileActivity.EXTRA_TAB_TASKS, true)
            )
        }
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                populateDrawer()
                val pinned = PapanStore.count(this@NativeChatActivity)
                findViewById<Button>(R.id.btnDrawerPapan).text =
                    if (pinned > 0) "${getString(R.string.papan_title)} ($pinned)"
                    else getString(R.string.papan_title)
            }
        })

        for (chipId in listOf(R.id.chip1, R.id.chip2, R.id.chip3, R.id.chip4)) {
            findViewById<Button>(chipId).setOnClickListener { v ->
                input.setText((v as Button).text.toString())
                input.requestFocus()
            }
        }

        // Aksi dari notifikasi berita: Tanya AI / Dengarkan
        intent.getStringExtra(DailySummaryReceiver.EXTRA_ASK_TITLE)?.let { title ->
            pendingArticleTitle = title
            pendingArticleLink = intent.getStringExtra(DailySummaryReceiver.EXTRA_ASK_LINK).orEmpty()
            input.setText("Jelaskan secara profesional berita ini: $title")
            input.setSelection(input.text.length)
            intent.removeExtra(DailySummaryReceiver.EXTRA_ASK_TITLE)
            intent.removeExtra(DailySummaryReceiver.EXTRA_ASK_LINK)
        }
        intent.getStringExtra(DailySummaryReceiver.EXTRA_SPEAK_TITLE)?.let { speak ->
            input.postDelayed({
                try {
                    tts?.speak(
                        speak,
                        android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                        null,
                        "news_tts"
                    )
                } catch (e: Exception) {
                }
            }, 1200)
            intent.removeExtra(DailySummaryReceiver.EXTRA_SPEAK_TITLE)
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        apiKey = prefs.getString(KEY_API, null)
        model = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        keyType = prefs.getString(KEY_TYPE, null)
        packageName = prefs.getString(KEY_PLAN_NAME, null)?.ifEmpty { null }
        isPaygKey = prefs.getBoolean(KEY_PAYG, false)
        showAllModels = prefs.getBoolean(KEY_SHOW_ALL, false)
        try {
            val arr = JSONArray(prefs.getString(KEY_ALLOWED, "[]") ?: "[]")
            if (arr.length() > 0) {
                val l = mutableListOf<String>()
                for (i in 0 until arr.length()) l.add(arr.optString(i))
                allowedModels = l
            }
        } catch (_: Exception) {
        }

        handleConnectIntent(intent)

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
        renderExampleCommands()
        findViewById<Button>(R.id.btnHealthCheck).setOnClickListener { healthCheckDialog() }
        findViewById<Button>(R.id.btnPlanDay).setOnClickListener { planMyDay() }
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
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, null)
        val g = greetingBase() + if (name.isNullOrEmpty()) "" else ", $name"
        findViewById<TextView>(R.id.txtHeaderGreeting).text = g
        if (!hasMessages) {
            renderTodayTasks()
            renderDailyDashboard()
        }
    }

    /**
     * Fitur #11: Dashboard Dinamis.
     * Pengguna baru → welcome sederhana ("Ada yang bisa saya bantu?").
     * Pengguna aktif → ringkasan personal: kebiasaan pakai app, tugas,
     * keuangan bulan ini, kesehatan, dan aktivitas WhatsApp terakhir.
     */
    private fun renderDailyDashboard() {
        val container = findViewById<LinearLayout>(R.id.dashboardContainer) ?: return
        container.removeAllViews()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // Hitung berapa kali app dibuka + hari sejak install
        val openCount = prefs.getInt("open_count", 0) + 1
        prefs.edit().putInt("open_count", openCount).apply()
        if (!prefs.contains("first_open_ts")) {
            prefs.edit().putLong("first_open_ts", System.currentTimeMillis()).apply()
        }
        val daysUsed = ((System.currentTimeMillis() - prefs.getLong("first_open_ts", 0L)) / 86_400_000L).toInt() + 1

        // Deteksi pengguna BARU: buka app sedikit + TIDAK ada jejak data sama sekali
        val sessions = try {
            org.json.JSONArray(prefs.getString(KEY_SESSIONS, "[]") ?: "[]")
        } catch (_: Exception) {
            org.json.JSONArray()
        }
        val hasData = sessions.length() > 0 ||
            prefs.getString(NativeChatActivity.KEY_API, null)?.isNotEmpty() == true ||
            AssistantData.loadTasks(this).isNotEmpty() ||
            AssistantData.loadFin(this).isNotEmpty() ||
            AssistantData.loadHealth(this).isNotEmpty()
        val isNew = openCount <= 2 && !hasData
        android.util.Log.i("SM_DASH", "renderDailyDashboard openCount=$openCount hasData=$hasData isNew=$isNew")
        renderNewsCard(container, prefs)
        renderHabitCard(container)
        if (isNew) return

        // Hero + subtitle dinamis untuk pengguna aktif
        val name = prefs.getString(KEY_NAME, null) ?: ""
        val hero = findViewById<TextView>(R.id.txtWelcomeHero)
        hero.text = if (name.isNotEmpty()) "Halo, $name! 👋" else greetingBase() + " 👋"
        findViewById<TextView>(R.id.txtWelcomeSubtitle).text = "Ini dashboard harianmu — makin sering dipakai, makin personal ✨"

        val lines = mutableListOf<String>()
        lines.add("🌟 ${if (name.isNotEmpty()) name else "Sahabat"} sudah $daysUsed hari bersama AI")
        val job = prefs.getString("profile_job", null)?.takeIf { it.isNotBlank() }
        if (!job.isNullOrEmpty()) lines[0] = lines[0] + " — $job"

        // Tugas hari ini
        val tasks = AssistantData.loadTasks(this).filter { !it.done }
        lines.add(if (tasks.isEmpty()) "✅ Tidak ada tugas tertunda — bersih!"
        else "✅ ${tasks.size} tugas menunggu hari ini")

        // Keuangan bulan ini
        val fin = AssistantData.loadFin(this)
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        var expense = 0.0; var income = 0.0
        for (f in fin) if (f.ts >= cal.timeInMillis) {
            if (f.type == "expense") expense += f.amount else income += f.amount
        }
        if (expense > 0 || income > 0) {
            lines.add(String.format(java.util.Locale("id"),
                "💰 Bulan ini: keluar Rp %,.0f · masuk Rp %,.0f", expense, income))
        }

        // Kesehatan terakhir
        try {
            AssistantData.loadHealth(this).lastOrNull()?.let { h ->
                val s = h.summary()
                if (s.isNotBlank()) lines.add("🩺 Terakhir: $s")
            }
        } catch (_: Exception) {
        }

        // Aktivitas WA terakhir (24 jam)
        val waTs = prefs.getLong("wa_last_incoming_ts", 0L)
        if (waTs > 0 && System.currentTimeMillis() - waTs < 86_400_000L) {
            val t = java.text.SimpleDateFormat("HH:mm").format(java.util.Date(waTs))
            lines.add("💬 WA masuk terakhir jam $t")
        }

        // Mode fokus
        if (prefs.getBoolean("focus_mode", false) &&
            prefs.getLong("focus_until", 0L) > System.currentTimeMillis()
        ) lines.add("🌙 Mode fokus sedang aktif")

        // Kartu dashboard
        val card = TextView(this)
        card.text = lines.joinToString("\n")
        card.setTextColor(getColor(R.color.textPrimary))
        card.textSize = 13f
        card.setLineSpacing(dp(2).toFloat(), 1.15f)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.background = getDrawable(R.drawable.bg_chip)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(10)
        card.layoutParams = lp
        card.setOnClickListener {
            input.setText("Beri saya ringkasan hari ini lengkap")
            input.requestFocus()
            hideTopbar()
        }
        container.addView(card)
        android.util.Log.i("SM_DASH", "dashboard card added, lines=${lines.size}")
    }

    /** Berita + video YouTube di beranda. Geser ke kiri untuk membuka kolom video. */
    private fun renderNewsCard(container: LinearLayout, prefs: android.content.SharedPreferences) {
        val topic = NewsFeed.detectTopic(
            prefs.getString("profile_job", null),
            prefs.getString("profile_hobby", null),
            prefs.getString("profile_goal", null)
        )
        val sections = DashboardSections.load(this)
        val showNews = sections.first
        val showYoutube = sections.second
        if (!showNews && !showYoutube) return

        container.addView(TextView(this).apply {
            text = when {
                showNews && showYoutube -> "📰 Berita  ·  ◀ geser ke kiri ▶  Video YouTube"
                showNews -> "📰 Berita Terkini"
                else -> "▶ Video YouTube"
            }
            textSize = 10f
            setTextColor(getColor(R.color.textSecondary))
            setPadding(dp(4), dp(8), dp(4), 0)
        })

        val pager = SnapHorizontalScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val pages = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pager.addView(pages)
        container.addView(pager)

        // Halaman 1: berita
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_chip)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(460)
            )
        }
        val newsSources = NewsFeed.loadSources(this)
        val ytQueries = YouTubeFeed.loadQueries(this)
        card.addView(TextView(this).apply {
            text = if (newsSources.isEmpty()) "📰 Berita Terkini · " + NewsFeed.label(topic)
            else "📰 Berita · " + newsSources.joinToString(", ")
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.primary))
        })
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(listBox)
        card.visibility = if (showNews) View.VISIBLE else View.GONE
        pages.addView(card)

        // Halaman 2: video YouTube
        val ytBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_chip)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(460)
            )
        }
        ytBox.addView(TextView(this).apply {
            text = if (ytQueries.isEmpty()) "▶ Video YouTube · " + NewsFeed.label(topic)
            else "▶ Video YouTube · " + ytQueries.joinToString(", ")
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.primary))
        })
        val ytList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ytBox.addView(ytList)
        ytBox.visibility = if (showYoutube) View.VISIBLE else View.GONE
        pages.addView(ytBox)

        pages.post {
            val w = pager.width
            if (w > 0) {
                card.layoutParams.width = w
                card.requestLayout()
                ytBox.layoutParams.width = w
                ytBox.requestLayout()
            }
        }

        // Slider otomatis: berita <-> YouTube tiap 7,5 detik (jalan hanya jika keduanya tampil).
        sliderRunnable?.let { sliderHandler.removeCallbacks(it) }
        sliderRunnable = null
        sliderEnabled = showNews && showYoutube
        if (sliderEnabled) {
            val slide = object : Runnable {
                override fun run() {
                    if (!sliderEnabled || isFinishing || isDestroyed) return
                    val w = pager.width
                    if (w <= 0) {
                        sliderHandler.postDelayed(this, 7500L)
                        return
                    }
                    val target = if (pager.scrollX < w / 2) w else 0
                    ValueAnimator.ofInt(pager.scrollX, target).apply {
                        duration = 650
                        addUpdateListener { pager.scrollTo(it.animatedValue as Int, 0) }
                        start()
                    }
                    sliderHandler.postDelayed(this, 7500L)
                }
            }
            sliderRunnable = slide
            pager.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        sliderHandler.removeCallbacks(slide)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        sliderHandler.removeCallbacks(slide)
                        sliderHandler.postDelayed(slide, 7500L)
                    }
                }
                false
            }
            sliderHandler.postDelayed(slide, 7500L)
        }

        fun drawItems(items: List<NewsItem>) {
            listBox.removeAllViews()
            val shown = items.take(6)
            for (i in shown.indices step 2) {
                val row = LinearLayout(this@NativeChatActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }
                for (j in i until minOf(i + 2, shown.size)) {
                    val n = shown[j]
                    val cell = LinearLayout(this@NativeChatActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = getDrawable(R.drawable.bg_preview)
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        ).apply {
                            if (j == i) rightMargin = dp(4) else leftMargin = dp(4)
                        }
                    }
                    val thumb = ImageView(this@NativeChatActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(70)
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        clipToOutline = true
                        background = getDrawable(R.drawable.bg_chip)
                    }
                    cell.addView(thumb)
                    cell.addView(TextView(this@NativeChatActivity).apply {
                        text = n.title
                        textSize = 11f
                        setTextColor(getColor(R.color.textPrimary))
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(6), 0, 0)
                    })
                    val meta = listOfNotNull(
                        n.source.takeIf { it.isNotBlank() },
                        n.timeAgo.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    cell.addView(TextView(this@NativeChatActivity).apply {
                        text = meta.ifBlank { " " }
                        textSize = 9.5f
                        setTextColor(getColor(R.color.textSecondary))
                        setPadding(0, dp(3), 0, 0)
                        minLines = 1
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    cell.setOnClickListener {
                        Toast.makeText(
                            this@NativeChatActivity,
                            "Membuka artikel…",
                            Toast.LENGTH_SHORT
                        ).show()
                        CoroutineScope(Dispatchers.IO).launch {
                            val url = NewsFeed.resolveLink(n.link)
                            withContext(Dispatchers.Main) {
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        this@NativeChatActivity,
                                        "Tidak bisa membuka berita",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                    row.addView(cell)
                    loadNewsThumb(thumb, n)
                }
                listBox.addView(row)
            }
        }

        val homeCacheKey = "home_${topic}_" + newsSources.joinToString("_")
        val cached = NewsFeed.loadFromPrefs(this@NativeChatActivity, homeCacheKey)
        if (showNews && cached.isNotEmpty()) {
            drawItems(cached)
        } else if (showNews) {
            listBox.addView(TextView(this).apply {
                text = "Memuat berita terbaru…"
                textSize = 11f
                setTextColor(getColor(R.color.textSecondary))
                setPadding(0, dp(8), 0, 0)
            })
        }

        if (showNews) CoroutineScope(Dispatchers.IO).launch {
            val fresh = try {
                NewsFeed.fetchHome(topic, 6, newsSources)
            } catch (e: Exception) {
                emptyList()
            }
            if (fresh.isNotEmpty()) NewsFeed.saveToPrefs(this@NativeChatActivity, homeCacheKey, fresh)
            withContext(Dispatchers.Main) {
                if (card.parent !== pages) return@withContext
                if (fresh.isNotEmpty()) {
                    drawItems(fresh)
                } else if (cached.isEmpty()) {
                    listBox.removeAllViews()
                    listBox.addView(TextView(this@NativeChatActivity).apply {
                        text = "Berita belum bisa dimuat. Cek koneksi internet."
                        textSize = 11f
                        setTextColor(getColor(R.color.textSecondary))
                        setPadding(0, dp(8), 0, 0)
                    })
                }
            }
        }

        val ytQuery = when (topic) {
            "saham" -> "IHSG saham Indonesia hari ini"
            "seller" -> "jualan online marketplace Indonesia tips"
            else -> "berita teknologi Indonesia hari ini"
        }

        fun drawVideos(videos: List<YouTubeVideo>) {
            ytList.removeAllViews()
            if (videos.isEmpty()) {
                ytList.addView(TextView(this@NativeChatActivity).apply {
                    text = "Video belum bisa dimuat. Cek koneksi internet."
                    textSize = 11f
                    setTextColor(getColor(R.color.textSecondary))
                    setPadding(0, dp(8), 0, 0)
                })
            } else {
                val shown = videos.take(6)
                for (i in shown.indices step 2) {
                    val row = LinearLayout(this@NativeChatActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(6) }
                    }
                    for (j in i until minOf(i + 2, shown.size)) {
                        val v = shown[j]
                        val cell = LinearLayout(this@NativeChatActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            background = getDrawable(R.drawable.bg_preview)
                            setPadding(dp(8), dp(8), dp(8), dp(8))
                            layoutParams = LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                            ).apply {
                                if (j == i) rightMargin = dp(4) else leftMargin = dp(4)
                            }
                        }
                        val thumb = ImageView(this@NativeChatActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, dp(70)
                            )
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            clipToOutline = true
                            background = getDrawable(R.drawable.bg_chip)
                        }
                        cell.addView(thumb)
                        cell.addView(TextView(this@NativeChatActivity).apply {
                            text = v.title
                            textSize = 11f
                            setTextColor(getColor(R.color.textPrimary))
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(0, dp(6), 0, 0)
                        })
                        cell.addView(TextView(this@NativeChatActivity).apply {
                            text = listOfNotNull(
                                v.channel.takeIf { it.isNotBlank() },
                                v.duration.takeIf { it.isNotBlank() }
                            ).joinToString(" · ") + if (v.channel.isNotBlank()) " · YouTube" else " YouTube"
                            textSize = 9.5f
                            setTextColor(getColor(R.color.textSecondary))
                            setPadding(0, dp(3), 0, 0)
                            minLines = 1
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        cell.setOnClickListener {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.url)))
                            } catch (e: Exception) {
                                Toast.makeText(this@NativeChatActivity, "Tidak bisa membuka YouTube", Toast.LENGTH_SHORT).show()
                            }
                        }
                        row.addView(cell)
                        loadNewsThumb(thumb, NewsItem(
                            title = v.title, source = "YouTube", link = v.thumb,
                            published = 0L, timeAgo = "", imageUrl = v.thumb
                        ))
                    }
                    ytList.addView(row)
                }
            }
        }

        if (showYoutube) {
            ytList.addView(TextView(this).apply {
                text = "Memuat video…"
                textSize = 11f
                setTextColor(getColor(R.color.textSecondary))
                setPadding(0, dp(8), 0, 0)
            })
        }
        if (showYoutube) CoroutineScope(Dispatchers.IO).launch {
            val vids = try {
                if (ytQueries.isNotEmpty()) YouTubeFeed.searchMany(ytQueries, 6)
                else YouTubeFeed.search(ytQuery, 6)
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (ytBox.parent === pages) drawVideos(vids)
            }
        }
    }

    /** Muat thumbnail kecil dari gambar artikel sumber (og:image / img RSS). */
    private fun loadNewsThumb(iv: ImageView, item: NewsItem) {
        CoroutineScope(Dispatchers.IO).launch {
            val url = try { NewsFeed.resolveImage(item) } catch (e: Exception) { "" }
            val bmp = if (url.isNotBlank()) try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) SahabatMobile/1.2.2")
                if (conn.responseCode == 200) BitmapFactory.decodeStream(conn.inputStream) else null
            } catch (e: Exception) {
                null
            } else null
            withContext(Dispatchers.Main) {
                if (bmp != null) {
                    iv.setImageBitmap(bmp)
                    iv.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Ringkasan kebiasaan dari riwayat chat, tampil di bawah berita. */
    private fun renderHabitCard(container: LinearLayout) {
        val base = HabitInsight.analyze(this)
        if (base.messageCount < 1) return

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_chip)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = "🧠 Kebiasaanmu"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.primary))
        })
        val body = TextView(this).apply {
            text = HabitInsight.cached(this@NativeChatActivity) ?: base.text
            textSize = 12f
            setTextColor(getColor(R.color.textPrimary))
            setLineSpacing(dp(2).toFloat(), 1.1f)
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(body)
        card.addView(TextView(this).apply {
            text = "Dari ${base.messageCount} pesan terakhir · AI menyesuaikan bantuan untukmu"
            textSize = 10f
            setTextColor(getColor(R.color.textSecondary))
            setPadding(0, dp(6), 0, 0)
        })
        container.addView(card)

        CoroutineScope(Dispatchers.IO).launch {
            val ai = HabitInsight.summarizeWithAi(
                this@NativeChatActivity,
                HabitInsight.userMessages(this@NativeChatActivity)
            )
            if (!ai.isNullOrBlank()) HabitInsight.save(this@NativeChatActivity, ai)
            withContext(Dispatchers.Main) {
                if (card.parent !== container) return@withContext
                if (!ai.isNullOrBlank()) body.text = ai
            }
        }
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

    override fun onPause() {
        super.onPause()
        sliderRunnable?.let { sliderHandler.removeCallbacks(it) }
    }

    override fun onResume() {
        super.onResume()
        if (sliderEnabled) {
            sliderRunnable?.let { r ->
                sliderHandler.removeCallbacks(r)
                sliderHandler.postDelayed(r, 7500L)
            }
        }
        assistant = try {
            JSONObject(
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_ASSISTANT, null) ?: "{}"
            )
        } catch (_: Exception) {
            JSONObject()
        }
        applyAssistantLabel()
        applySettingsFromProfile()
    }

    /**
     * Ambil perubahan yang dibuat di layar "Profil & Pengaturan"
     * (API key, model, lebar tampilan) tanpa perlu restart.
     */
    private fun applySettingsFromProfile() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val newKey = prefs.getString(KEY_API, null)
        if (newKey != apiKey) {
            apiKey = newKey
            keyType = prefs.getString(KEY_TYPE, null)
            if (!apiKey.isNullOrEmpty()) {
                showKeyType(keyType)
            }
        }

        val newModel = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        if (newModel != model) {
            model = newModel
            updateModelPill()
        }

        showAllModels = prefs.getBoolean(KEY_SHOW_ALL, false)

        val newWide = prefs.getBoolean(KEY_WIDE, false)
        if (newWide != wideMode) {
            wideMode = newWide
            rerenderBubbles()
        }
        renderTodayTasks()
    }

    /** Render ulang bubble dengan lebar saat ini. */
    private fun rerenderBubbles() {
        val msgs = JSONArray()
        for (i in 0 until history.length()) msgs.put(history.getJSONObject(i))
        bubbleContainer.removeAllViews()
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            addBubble(
                m.optString("content"),
                m.optString("role") == "user",
                m.optString("image").ifEmpty { null }
            )
        }
        updateWelcome()
    }

    /**
     * Model AI hanya bisa dipilih di satu tempat: tab "Pengaturan"
     * pada layar Profil & Pengaturan. Dari layar chat kita arahkan ke sana.
     */
    private fun openSettingsTab() {
        startActivity(
            Intent(this, ProfileActivity::class.java)
                .putExtra(ProfileActivity.EXTRA_TAB_SETTINGS, true)
        )
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

    @SuppressLint("SetTextI18n")
    /** Tema warna markdown yang mengikuti mode terang/gelap. */
    private fun markdownTheme(): Markdown.Theme = Markdown.Theme(
        text = getColor(R.color.textPrimary),
        accent = getColor(R.color.primary),
        muted = getColor(R.color.textSecondary),
        codeBg = getColor(R.color.primaryTint),
        codeText = getColor(R.color.primaryDeep),
        bullet = getColor(R.color.primary),
        divider = getColor(R.color.stroke),
        tip = getColor(R.color.gold),
        quote = getColor(R.color.textSecondary)
    )

    /** Terapkan markdown ke bubble AI; gambar ![](url) dimuat async lalu disisipkan sebagai ImageSpan. */
    private fun applyMarkdown(tv: TextView, text: String) {
        val rendered = Markdown.render(text, markdownTheme())
        tv.text = rendered
        if (rendered is android.text.Spannable) {
            val refs = rendered.getSpans(0, rendered.length, Markdown.ImageRef::class.java)
            for (ref in refs) {
                val url = ref.url
                val cached = Markdown.bitmapCache[url]
                if (cached != null) {
                    insertImageSpan(rendered, ref, cached, tv)
                } else {
                    CoroutineScope(Dispatchers.IO).launch {
                        val bmp = MarketSearch.loadImage(url)
                        if (bmp != null) {
                            Markdown.bitmapCache[url] = bmp
                            withContext(Dispatchers.Main) {
                                // Span mungkin berpindah posisi saat teks streaming → cari ulang
                                val fresh = (tv.text as? android.text.Spannable)
                                    ?.getSpans(0, tv.text.length, Markdown.ImageRef::class.java)
                                    ?.firstOrNull { it.url == url } ?: return@withContext
                                insertImageSpan(tv.text as android.text.Spannable, fresh, bmp, tv)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun insertImageSpan(
        spannable: android.text.Spannable,
        ref: Markdown.ImageRef,
        bmp: android.graphics.Bitmap,
        tv: TextView
    ) {
        val st = spannable.getSpanStart(ref)
        val en = spannable.getSpanEnd(ref)
        if (st < 0 || en <= st) return
        spannable.removeSpan(ref)
        val d = android.graphics.drawable.BitmapDrawable(resources, bmp)
        val w = dp(230)
        val h = (bmp.height.toLong() * w / bmp.width.coerceAtLeast(1)).toInt().coerceIn(dp(60), dp(320))
        d.setBounds(0, 0, w, h)
        spannable.setSpan(
            android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM),
            st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tv.invalidate()
    }

    private fun addBubble(text: String, isUser: Boolean, imagePath: String? = null): TextView {
        val tv = TextView(this)
        tv.background = getDrawable(
            if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_ai
        )
        tv.setTextColor(getColor(if (isUser) R.color.textOnBrand else R.color.textPrimary))
        tv.textSize = if (isUser) 15f else 15f
        tv.setLineSpacing(5f * resources.displayMetrics.density, 1.06f)
        val pad = resources.getDimensionPixelSize(R.dimen.bubble_padding)
        tv.setPadding(pad, pad, pad, pad)
        // Lebar maksimal: mode "lebar" memakai hampir seluruh layar, mode "padat" 80%
        val screenW = resources.displayMetrics.widthPixels
        tv.maxWidth = if (isUser) (screenW * 0.80).toInt() else (screenW * 0.94).toInt()
        if (!isUser) {
            // Efek bayangan tipis agar bubble terasa terpisah dari latar
            tv.elevation = dp(1).toFloat()
        }
        if (isUser) {
            tv.text = text
        } else {
            applyMarkdown(tv, text)
            tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            tv.highlightColor = getColor(R.color.primaryTint)
        }
        tv.setOnLongClickListener {
            if (isUser) {
                copyToClipboard(tv.text.toString())
            } else {
                // Fitur #5: long-press balasan AI → dengarkan dengan suara
                speak(tv.text.toString())
                Toast.makeText(this, "🔊 Membacakan balasan...", Toast.LENGTH_SHORT).show()
            }
            true
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = if (isUser) Gravity.END else Gravity.TOP

        if (!isUser) {
            val avatar = ImageView(this)
            avatar.setImageResource(R.drawable.ic_premium)
            avatar.background = getDrawable(R.drawable.bg_premium_circle)
            avatar.setPadding(dp(4), dp(4), dp(4), dp(4))
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
            name.letterSpacing = 0.06f
            name.setTextColor(getColor(R.color.primary))
            name.setPadding(dp(2), 0, 0, dp(4))
            column.addView(name)
            column.addView(tv)
            // Mode lebar: kolom mengisi sisa lebar layar (maksimalkan kiri-kanan)
            if (wideMode) {
                val colParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                column.layoutParams = colParams
                tv.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
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

        /** Konversi hasil pencarian produk menjadi kartu untuk Papan Hasil AI. */
    private fun List<MarketProduct>.toPapanCards(keyword: String): List<PapanStore.Card> = map { p ->
        val hasInfo = p.price.isNotEmpty() || p.sold > 0 || (p.rating ?: 0.0) > 0.0
        PapanStore.Card(
            title = p.title,
            url = p.url,
            image = p.image,
            rating = p.rating ?: 0.0,
            sold = p.sold,
            price = p.price,
            keyword = keyword,
            market = p.market.name.lowercase(),
            // Sudah dicek saat pencarian -> papan tidak perlu fetch ulang.
            checked = hasInfo
        )
    }

    /**
     * Kartu saran produk marketplace — tampil di chat setelah tool market_search.
     * Tap salah satu produk → langsung dibuka di app Shopee.
     */
    private fun addProductCard(
        keyword: String,
        products: List<MarketProduct>,
        pinned: Int = 0,
        foodMode: Boolean = false,
        foodApps: List<FoodApp> = emptyList()
    ) {
        if (products.isEmpty()) return
        val markets = products.map { it.market }.distinct()
        val marketLabel = if (markets.size == 1) markets.first().label
        else markets.joinToString(", ") { it.label }
        val pad = resources.getDimensionPixelSize(R.dimen.bubble_padding)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = getDrawable(R.drawable.bg_bubble_ai)
        card.elevation = dp(1).toFloat()
        card.setPadding(pad, pad, pad, pad)

        val header = TextView(this)
        header.text = if (foodMode) "🍜 ShopeeFood — \"$keyword\""
        else "🛒 Saran Produk $marketLabel — \"$keyword\""
        header.textSize = 13f
        header.setTypeface(null, android.graphics.Typeface.BOLD)
        header.setTextColor(getColor(R.color.primary))
        card.addView(header)

        products.forEachIndexed { idx, p ->
            val itemRow = LinearLayout(this)
            itemRow.orientation = LinearLayout.HORIZONTAL
            itemRow.gravity = Gravity.CENTER_VERTICAL
            itemRow.setPadding(dp(6), dp(8), dp(6), dp(8))
            itemRow.background = getDrawable(R.drawable.bg_preview)

            if (p.image != null) {
                val thumb = ImageView(this)
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                thumb.clipToOutline = true
                thumb.background = getDrawable(R.drawable.bg_preview)
                thumb.contentDescription = p.title
                thumb.layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
                (thumb.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(10)
                CoroutineScope(Dispatchers.IO).launch {
                    val bmp = MarketSearch.loadImage(p.image)
                    if (bmp != null) {
                        withContext(Dispatchers.Main) { thumb.setImageBitmap(bmp) }
                    }
                }
                itemRow.addView(thumb)
            }

            val num = TextView(this)
            num.text = "${idx + 1}.  "
            num.textSize = 14f
            num.setTextColor(getColor(R.color.primary))
            num.setTypeface(null, android.graphics.Typeface.BOLD)
            itemRow.addView(num)

            val infoCol = LinearLayout(this)
            infoCol.orientation = LinearLayout.VERTICAL
            infoCol.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val badge = TextView(this)
            badge.text = p.market.label
            badge.textSize = 9.5f
            badge.setTypeface(null, android.graphics.Typeface.BOLD)
            badge.setTextColor(getColor(R.color.textSecondary))
            infoCol.addView(badge)

            val title = TextView(this)
            title.text = p.title
            title.textSize = 13.5f
            title.setTextColor(getColor(R.color.textPrimary))
            title.maxLines = 2
            title.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            infoCol.addView(title)

            // Harga + rating + terjual (hasil pembacaan halaman produk).
            if (p.price.isNotEmpty()) {
                val priceTv = TextView(this)
                priceTv.text = p.price
                priceTv.textSize = 12f
                priceTv.setTypeface(null, android.graphics.Typeface.BOLD)
                priceTv.setTextColor(getColor(R.color.primary))
                infoCol.addView(priceTv)
            }
            val metaParts = mutableListOf<String>()
            if ((p.rating ?: 0.0) > 0) {
                metaParts.add(
                    "★ " + String.format(java.util.Locale.getDefault(), "%.1f", p.rating)
                        .replace('.', ',')
                )
            }
            if (p.sold > 0) metaParts.add("Terjual " + MarketSearch.formatCount(p.sold))
            if (metaParts.isNotEmpty()) {
                val meta = TextView(this)
                meta.text = metaParts.joinToString("  ·  ")
                meta.textSize = 10.5f
                meta.setTextColor(getColor(R.color.textSecondary))
                infoCol.addView(meta)
            }
            itemRow.addView(infoCol)

            val arrow = TextView(this)
            arrow.text = "  ›"
            arrow.textSize = 16f
            arrow.setTextColor(getColor(R.color.primary))
            itemRow.addView(arrow)

            itemRow.setOnClickListener {
                MarketSearch.openInMarket(this, p.url)
                Toast.makeText(this, "Membuka di app ${p.market.label}...", Toast.LENGTH_SHORT).show()
            }
            card.addView(itemRow)
        }

        if (foodMode) {
            val appRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), 0)
            }
            val chips = mutableListOf<Pair<String, () -> Unit>>()
            foodApps.filter { it.label.equals("ShopeeFood", true) }.forEach { app ->
                chips.add("Buka di ${app.label}" to {
                    if (!FoodApps.open(this@NativeChatActivity, app)) {
                        Toast.makeText(this@NativeChatActivity, "Gagal membuka ${app.label}", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            chips.add("Buka di Shopee" to { MarketSearch.openInMarket(this@NativeChatActivity, products.first().url) })
            chips.add("Lihat di Google Maps" to {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://www.google.com/maps/search/" + URLEncoder.encode("$keyword makanan", "UTF-8")
                )))
            })
            chips.forEach { (label, action) ->
                appRow.addView(TextView(this).apply {
                    text = label
                    textSize = 10.5f
                    setTextColor(getColor(R.color.primary))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = getDrawable(R.drawable.bg_chip)
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(6) }
                    setOnClickListener { action() }
                })
            }
            card.addView(appRow)
        }

        // Footer: petunjuk + tombol simpan ke Papan Hasil AI
        val footerRow = LinearLayout(this)
        footerRow.orientation = LinearLayout.HORIZONTAL
        footerRow.gravity = Gravity.CENTER_VERTICAL
        val fp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        fp.topMargin = dp(8)
        footerRow.layoutParams = fp

        val footer = TextView(this)
        footer.text = if (markets.size == 1) getString(R.string.market_tap_hint, marketLabel)
        else getString(R.string.market_tap_hint_multi)
        footer.textSize = 10.5f
        footer.setTextColor(getColor(R.color.textSecondary))
        footer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        footerRow.addView(footer)

        val pin = TextView(this)
        // Produk sudah otomatis tersimpan saat kartu dibuat.
        pin.text = getString(if (pinned > 0) R.string.papan_pin_done else R.string.papan_pin)
        pin.textSize = 11.5f
        pin.setTextColor(getColor(if (pinned > 0) R.color.successText else R.color.primary))
        pin.setTypeface(null, android.graphics.Typeface.BOLD)
        pin.background = getDrawable(R.drawable.bg_chip)
        pin.setPadding(dp(10), dp(6), dp(10), dp(6))
        pin.isClickable = true
        pin.setOnClickListener {
            val n = PapanStore.pin(this, products.toPapanCards(keyword))
            val msg = if (n > 0) getString(R.string.papan_pinned, n) else getString(R.string.papan_pin_dup)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            if (n > 0) {
                pin.text = getString(R.string.papan_pin_done)
                pin.setTextColor(getColor(R.color.successText))
            }
        }
        footerRow.addView(shareChip {
            buildString {
                append(if (foodMode) "🍜 Rekomendasi makanan" else "🛒 Rekomendasi produk")
                append(" — \"").append(keyword).append("\"\n")
                products.forEachIndexed { idx, p ->
                    append(idx + 1).append(". ").append(p.title)
                    if (p.price.isNotBlank()) append(" — ").append(p.price)
                    append("\n").append(p.url).append("\n")
                }
                append("— dikirim dari SahabatMobile AI")
            }
        })
        footerRow.addView(pin)
        card.addView(footerRow)

        // Bungkus dengan avatar & label seperti bubble AI
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.TOP

        val avatar = ImageView(this)
        avatar.setImageResource(R.drawable.ic_premium)
        avatar.background = getDrawable(R.drawable.bg_premium_circle)
        avatar.setPadding(dp(4), dp(4), dp(4), dp(4))
        avatar.contentDescription = getString(R.string.brand_small)
        val avParams = LinearLayout.LayoutParams(dp(30), dp(30))
        avParams.marginEnd = dp(8)
        avatar.layoutParams = avParams
        row.addView(avatar)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = getString(R.string.brand_small)
        name.textSize = 10.5f
        name.letterSpacing = 0.06f
        name.setTextColor(getColor(R.color.primary))
        name.setPadding(dp(2), 0, 0, dp(4))
        column.addView(name)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        column.addView(card)
        val colParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        column.layoutParams = colParams
        row.addView(column)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = resources.getDimensionPixelSize(R.dimen.bubble_margin)
        row.layoutParams = params
        bubbleContainer.addView(row)
        scrollToBottom()
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
            "Umum", "Teman Curhat", "Dokter Umum", "Ahli Gizi", "Teman Ngobrol",
            "Motivator", "Pemberi Saran", "Owner Bisnis", "Manager Bisnis / Operasional",
            "Akuntan & Manajer Keuangan", "Konten Kreator", "Guru & Pengajar",
            "Belajar & Ujian", "Bahasa Inggris & Lainnya"
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

    /**
     * Bangun konteks profil pengguna untuk disuntikkan ke system prompt AI,
     * sehingga asisten pribadi mengetahui umur, pekerjaan, hobi, riwayat kesehatan,
     * dan tujuan pengguna — lalu bisa memberi saran yang sesuai.
     */
    private fun profileContext(): String {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val birth = p.getLong("profile_birth", 0L)
        val job = p.getString("profile_job", "") ?: ""
        val hobby = p.getString("profile_hobby", "") ?: ""
        val health = p.getString("profile_health", "") ?: ""
        val goal = p.getString("profile_goal", "") ?: ""
        val tasksCtx = tasksContext()
        val healthCtx = healthContext()
        if (birth == 0L && job.isEmpty() && hobby.isEmpty() && health.isEmpty() &&
            goal.isEmpty() && tasksCtx.isEmpty() && healthCtx.isEmpty()
        ) return ""

        val sb = StringBuilder()
        sb.append("## Profil Pengguna (konteks pribadi, gunakan untuk saran yang tepat)\n")
        if (birth > 0L) {
            val cal = Calendar.getInstance().apply { timeInMillis = birth }
            val sdf = java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale("id", "ID"))
            val age = run {
                val now = Calendar.getInstance()
                var a = now.get(Calendar.YEAR) - cal.get(Calendar.YEAR)
                if (now.get(Calendar.DAY_OF_YEAR) < cal.get(Calendar.DAY_OF_YEAR)) a--
                a
            }
            sb.append("- Tanggal lahir: ${sdf.format(birth)}\n")
            sb.append("- Umur: $age tahun\n")
        }
        if (job.isNotEmpty()) sb.append("- Pekerjaan: $job\n")
        if (hobby.isNotEmpty()) sb.append("- Hobi & kegemaran: $hobby\n")
        if (health.isNotEmpty()) sb.append("- Riwayat kesehatan / keluhan: $health\n")
        if (goal.isNotEmpty()) sb.append("- Tujuan penggunaan aplikasi: $goal\n")
        sb.append("\nPENTING: sesuaikan saran kesehatan, kegiatan, dan pekerjaan dengan umur & profil di atas. ")
        sb.append("Jangan beri diagnosis medis resmi; anjurkan tenaga kesehatan untuk gejala berat.")
        if (tasksCtx.isNotEmpty()) sb.append("\n\n").append(tasksCtx)
        if (healthCtx.isNotEmpty()) sb.append("\n\n").append(healthCtx)
        return sb.toString()
    }

    /** Daftar tugas & pengingat milik pengguna → supaya AI tahu dan bisa mengingatkan. */
    private fun tasksContext(): String {
        val tasks = AssistantData.loadTasks(this)
        if (tasks.isEmpty()) return ""
        val pending = tasks.filter { !it.done }
        if (pending.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## Tugas & Pengingat pengguna (bantu beri saran & ingatkan)\n")
        for (t in pending.take(15)) {
            val waktu = if (t.at > 0L) " (${t.timeLabel()})" else ""
            val ulang = if (t.repeatDaily) " [harian]" else ""
            sb.append("- ${t.text}$waktu$ulang\n")
        }
        sb.append("Kalau pengguna bertanya tentang jadwal, hari ini, atau \"apa tugas saya\", ")
        sb.append("gunakan daftar ini. Kalau waktu suatu tugas sudah lewat/sekarang, ingatkan dia dengan sopan.")
        return sb.toString()
    }

    /** Catatan kesehatan terakhir → AI bisa melacak tren dan memberi saran nyata. */
    private fun healthContext(): String {
        val notes = AssistantData.loadHealth(this).sortedByDescending { it.ts }.take(7)
        if (notes.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## Catatan kesehatan pengguna (terbaru, gunakan untuk memantau tren)\n")
        for (n in notes) {
            val s = n.summary()
            if (s.isNotEmpty()) sb.append("- ${n.dateLabel()}: $s\n")
        }
        sb.append("Catatan: jangan memberi diagnosis; beri saran gaya hidup dan tandai bila perlu dokter.")
        return sb.toString()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Saat mengetik input membesar jelas; saat kosong / tidak fokus mengecil sendiri. */
    private fun resizeInputToText() {
        val base = if (inputBaseHeight > 0) inputBaseHeight else dp(46)
        val hasText = !input.text.isNullOrBlank()
        val target = if (!inputFocused || !hasText) {
            base
        } else {
            val line = maxOf(input.lineHeight, dp(20))
            val padding = input.paddingTop + input.paddingBottom
            (maxOf(3, input.lineCount) * line + padding).coerceIn(dp(108), dp(160))
        }
        if (input.layoutParams.height != target) {
            val lp = input.layoutParams
            lp.height = target
            input.layoutParams = lp
        }
    }

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
            // Fitur #6: tanya mode analisis gambar
            showScanModeDialog()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.image_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Fitur #6: pilih jenis analisis untuk gambar yang di-attach. */
    private fun showScanModeDialog() {
        val labels = arrayOf(
            "📷 Analisis Kalori (foto makanan)",
            "🧾 Catat Nota / Struk Belanja",
            "🛍️ Buat Deskripsi Produk Jualan",
            "📄 Ekstrak Teks (OCR)",
            "❌ Tanpa Mode — kirim biasa"
        )
        val keys = arrayOfNulls<String>(5)
        keys[0] = "kalori"; keys[1] = "nota"; keys[2] = "produk"; keys[3] = "teks"; keys[4] = null
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📸 Pilih Analisis Gambar")
            .setItems(labels) { _, which -> pendingScanMode = keys[which] }
            .setOnCancelListener { pendingScanMode = null }
            .show()
    }

    /** Fitur #6: instruksi AI sesuai mode pemindaian. */
    private fun scanInstructionFor(mode: String?): String? = when (mode) {
        "kalori" -> "Analisis gambar makanan ini: identifikasi setiap item makanan, estimasi kalori, protein, karbohidrat, dan lemak per porsi, lalu beri penilaian singkat apakah sehat atau perlu dikurangi. Format ringkas per baris."
        "nota" -> "Ini nota/struk pembelian. Ekstrak semua item beserta harganya, hitung totalnya, dan tulis ringkasan pengeluaran. Jika ada tanggal dan nama toko, sebutkan."
        "produk" -> "Ini foto produk yang akan dijual. Buat deskripsi jualan menarik: nama produk, spesifikasi/keunggulan yang terlihat, dan caption promosi singkat siap posting di WhatsApp."
        "teks" -> "Baca dan ekstrak semua teks dari gambar ini (transkrip utuh), lalu berikan ringkasan singkat isinya."
        else -> null
    }

    /** Fitur #6: label singkat untuk bubble user. */
    private fun scanLabelFor(mode: String?): String? = when (mode) {
        "kalori" -> "📷 Scan Kalori"
        "nota" -> "🧾 Scan Nota"
        "produk" -> "🛍️ Scan Produk"
        "teks" -> "📄 Scan Teks"
        else -> null
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
        pendingScanMode = null
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

    // ---------------- ASISTEN PRIBADI: TUGAS, CONTOH, KESEHATAN ----------------

    /** Isi kartu "Tugas hari ini" di layar awal. */
    private fun renderTodayTasks() {
        val box = findViewById<LinearLayout>(R.id.taskTodayContainer) ?: return
        box.removeAllViews()
        val tasks = AssistantData.loadTasks(this).filter { !it.done }
        if (tasks.isEmpty()) {
            val tv = TextView(this)
            tv.text = getString(R.string.today_tasks_empty)
            tv.setTextColor(getColor(R.color.textSecondary))
            tv.textSize = 12.5f
            tv.setPadding(dp(4), 0, 0, 0)
            box.addView(tv)
            return
        }
        for (t in tasks.take(5)) {
            val row = TextView(this)
            val waktu = if (t.at > 0L) " · ${t.timeLabel()}" else ""
            val ulang = if (t.repeatDaily) " · harian" else ""
            row.text = "• ${t.text}$waktu$ulang"
            row.setTextColor(getColor(R.color.textPrimary))
            row.textSize = 13.5f
            row.setPadding(dp(4), dp(4), 0, dp(4))
            row.setOnClickListener {
                input.setText(getString(R.string.ask_task_status, t.text))
                input.requestFocus()
                hideTopbar()
            }
            box.addView(row)
        }
    }

    /** Contoh perintah yang bisa langsung dipakai user. */
    private fun renderExampleCommands() {
        val box = findViewById<LinearLayout>(R.id.exampleContainer) ?: return
        box.removeAllViews()
        val examples = listOf(
            getString(R.string.example_1),
            getString(R.string.example_2),
            getString(R.string.example_3),
            getString(R.string.example_4),
            getString(R.string.example_5)
        )
        for (ex in examples) {
            val tv = TextView(this)
            tv.text = ex
            tv.setTextColor(getColor(R.color.textPrimary))
            tv.textSize = 14f
            tv.setPadding(dp(16), dp(14), dp(16), dp(14))
            tv.background = getDrawable(R.drawable.bg_chip)
            tv.setOnClickListener {
                input.setText(ex)
                input.requestFocus()
                hideTopbar()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            tv.layoutParams = lp
            box.addView(tv)
        }
    }

    /** Dialog input cepat untuk catatan kesehatan harian → dikirim ke AI. */
    private fun healthCheckDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(10), dp(24), 0)

        val sleep = EditText(this)
        sleep.hint = getString(R.string.health_sleep_hint)
        sleep.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        box.addView(sleep)

        val weight = EditText(this)
        weight.hint = getString(R.string.health_weight_hint)
        weight.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        box.addView(weight)

        val bp = EditText(this)
        bp.hint = getString(R.string.health_bp_hint)
        box.addView(bp)

        val mood = EditText(this)
        mood.hint = getString(R.string.health_mood_hint)
        box.addView(mood)

        AlertDialog.Builder(this)
            .setTitle(R.string.health_check_title)
            .setView(box)
            .setPositiveButton(R.string.health_save) { _, _ ->
                val s = sleep.text.toString().trim().toFloatOrNull()
                val w = weight.text.toString().trim().toFloatOrNull()
                val b = bp.text.toString().trim().ifEmpty { null }
                val m = mood.text.toString().trim().ifEmpty { null }

                val note = AssistantData.HealthNote(
                    sleepHours = s,
                    weightKg = w,
                    bp = b,
                    mood = m
                )
                val list = AssistantData.loadHealth(this)
                list.add(note)
                AssistantData.saveHealth(this, list)

                val parts = mutableListOf<String>()
                s?.let { parts.add("tidur ${it.toInt()} jam") }
                w?.let { parts.add("berat ${it} kg") }
                b?.let { parts.add("tensi $b") }
                m?.let { parts.add("suasana/keluhan: $m") }
                val detail = if (parts.isEmpty()) "tidak ada data" else parts.joinToString(", ")
                input.setText(
                    "Cek kesehatan harian: $detail. Tolong analisis tren saya dan beri saran praktis " +
                        "untuk gaya hidup, tidur, dan aktivitas besok."
                )
                input.requestFocus()
                hideTopbar()
                Toast.makeText(this, R.string.health_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    /** Pasang API key otomatis dari deep link website: sahabatmobile://connect?key=... */
    private fun handleConnectIntent(i: Intent?) {
        val data = i?.data ?: return
        if (data.scheme != "sahabatmobile" || data.host != "connect") return
        val key = data.getQueryParameter("key")?.trim().orEmpty()
        if (key.isBlank()) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_API, key).apply()
        apiKey = key
        i.data = null
        Toast.makeText(this, "API key berhasil dipasang. Mendeteksi paket...", Toast.LENGTH_LONG).show()
        detectAccess()
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
        modelPill.text = getString(R.string.model_pill_hint, label)
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
        headerBasePadTop = header.paddingTop
        rootBasePadTop = root.paddingTop
        val rootPadBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topInset = bars.top
            applyInsetPadding()
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

    /** Space status bar pindah ke root saat topbar disembunyikan. */
    private fun applyInsetPadding() {
        val root = findViewById<View>(R.id.rootContent)
        val header = findViewById<View>(R.id.headerBar)
        if (headerVisible) {
            header.setPadding(
                header.paddingLeft,
                headerBasePadTop + topInset,
                header.paddingRight,
                header.paddingBottom
            )
            root.setPadding(root.paddingLeft, rootBasePadTop, root.paddingRight, root.paddingBottom)
        } else {
            header.setPadding(
                header.paddingLeft,
                headerBasePadTop,
                header.paddingRight,
                header.paddingBottom
            )
            root.setPadding(
                root.paddingLeft,
                rootBasePadTop + topInset,
                root.paddingRight,
                root.paddingBottom
            )
        }
    }

    // ---------------- TOPBAR (bisa disembunyikan) ----------------

    /**
     * Scroll ke atas → topbar muncul, scroll ke bawah → topbar sembunyi.
     */
    private fun attachAutoTopbar(scroller: android.widget.ScrollView) {
        var lastY = 0
        scroller.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val dy = scrollY - lastY
            if (kotlin.math.abs(dy) >= 8) {
                if (dy < 0) showTopbar() else hideTopbar()
                lastY = scrollY
            }
        }
    }

    /** Tampilkan topbar (menggeser dari atas). */
    private fun showTopbar() {
        if (headerVisible) return
        headerVisible = true
        headerBar.visibility = View.VISIBLE
        applyInsetPadding()
        headerBar.post {
            headerBar.alpha = 0f
            headerBar.translationY = -headerBar.height.toFloat()
            headerBar.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
        floatControls.animate().alpha(0f).setDuration(140)
            .withEndAction { floatControls.visibility = View.GONE }.start()
    }

    /** Sembunyikan topbar (menggeser ke atas). */
    private fun hideTopbar(animate: Boolean = true) {
        if (!headerVisible) return
        headerVisible = false
        if (!animate) {
            headerBar.visibility = View.GONE
            applyInsetPadding()
            floatControls.visibility = View.VISIBLE
            floatControls.alpha = 1f
            return
        }
        val h = headerBar.height
        headerBar.animate().alpha(0f).translationY(-h.toFloat()).setDuration(190)
            .withEndAction {
                headerBar.visibility = View.GONE
                applyInsetPadding()
                floatControls.visibility = View.VISIBLE
                floatControls.alpha = 0f
                floatControls.animate().alpha(1f).setDuration(170).start()
            }.start()
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.smoothScrollTo(0, bubbleContainer.height) }
    }

    /** Buka WhatsApp dengan teks siap kirim. */
    private fun shareToWhatsApp(text: String) {
        val url = "https://api.whatsapp.com/send?text=" + URLEncoder.encode(text.take(4000), "UTF-8")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "WhatsApp tidak tersedia", Toast.LENGTH_SHORT).show()
        }
    }

    /** Chip "Bagikan" untuk kartu-kartu hasil AI. */
    private fun shareChip(textProvider: () -> String): TextView = TextView(this).apply {
        text = "Bagikan"
        textSize = 10.5f
        setTextColor(getColor(R.color.primary))
        setTypeface(null, android.graphics.Typeface.BOLD)
        background = getDrawable(R.drawable.bg_chip)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(6) }
        setOnClickListener { shareToWhatsApp(textProvider()) }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.brand_small), text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private data class NewsShortcode(val sources: List<String>)

    private fun parseNewsShortcode(text: String): NewsShortcode? {
        if (Regex("""\[\s*(?:reset|hapus|kembali|default)\s+berita\s*\]""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
        ) return NewsShortcode(emptyList())
        val m = Regex("""\[\s*(?:ganti|set|ubah|pilih|pakai)\s+berita\s+([^\]]+)\]""", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        val sources = m.groupValues[1]
            .split(Regex("""\s*(?:dan|,|&|/|\|)\s*"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(6)
        return if (sources.isEmpty()) null else NewsShortcode(sources)
    }

    private fun handleNewsShortcode(sc: NewsShortcode) {
        NewsFeed.saveSources(this, sc.sources)
        NewsFeed.clearHomeCache(this)
        updateWelcome()
        if (sc.sources.isEmpty()) {
            addBubble(
                "✅ Filter sumber berita dihapus. Beranda kembali menampilkan berita dari semua sumber.",
                isUser = false
            )
        } else {
            addBubble(buildString {
                append("✅ Sumber berita beranda diubah:\n")
                sc.sources.forEach { append("• ").append(it).append("\n") }
                append("\nBuka chat baru untuk melihat berita dari sumber tersebut. ")
                append("Ketik [reset berita] untuk kembali.")
            }, isUser = false)
        }
    }

    private fun parseYoutubeShortcode(text: String): List<String>? {
        if (Regex("""\[\s*(?:reset|hapus|kembali|default)\s+youtube\s*\]""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
        ) return emptyList()
        val m = Regex("""\[\s*(?:youtube|yt|video)\s+([^\]]+)\]""", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        val queries = m.groupValues[1]
            .split(Regex("""\s*(?:,|;|\bdan\b)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(5)
        return if (queries.isEmpty()) null else queries
    }

    private fun handleYoutubeShortcode(queries: List<String>) {
        YouTubeFeed.saveQueries(this, queries)
        updateWelcome()
        if (queries.isEmpty()) {
            addBubble(
                "✅ Topik video YouTube dihapus. Beranda kembali memakai topik sesuai profilmu.",
                isUser = false
            )
        } else {
            addBubble(buildString {
                append("✅ Topik video YouTube diubah:\n")
                queries.forEach { append("• ").append(it).append("\n") }
                append("\nBuka chat baru untuk melihat videonya. Ketik [reset youtube] untuk kembali.")
            }, isUser = false)
        }
    }

    private data class SectionShortcode(val news: Boolean?, val youtube: Boolean?)

    private fun parseSectionShortcode(text: String): SectionShortcode? {
        var news: Boolean? = null
        var yt: Boolean? = null
        Regex("""\[([^\]]+)\]""").findAll(text).forEach { m ->
            val s = m.groupValues[1].lowercase()
            val hide = Regex("""\b(sembunyikan|sembunyi|hide|matikan|nonaktifkan)\b""").containsMatchIn(s)
            val show = Regex("""\b(tampilkan|tampil|show|aktifkan|munculkan)\b""").containsMatchIn(s)
            if (!hide && !show) return@forEach
            val visible = show
            when {
                Regex("""\b(keduanya|semua|both)\b""").containsMatchIn(s) -> {
                    news = visible; yt = visible
                }
                Regex("""\b(berita|news)\b""").containsMatchIn(s) -> news = visible
                Regex("""\b(youtube|yt|video)\b""").containsMatchIn(s) -> yt = visible
            }
        }
        return if (news == null && yt == null) null else SectionShortcode(news, yt)
    }

    private fun handleSectionShortcode(sc: SectionShortcode) {
        val cur = DashboardSections.load(this)
        val showNews = sc.news ?: cur.first
        val showYt = sc.youtube ?: cur.second
        DashboardSections.save(this, showNews, showYt)
        updateWelcome()
        addBubble(buildString {
            append("✅ Tampilan beranda diubah:\n")
            append("• Berita: ").append(if (showNews) "ditampilkan" else "disembunyikan").append("\n")
            append("• Video YouTube: ").append(if (showYt) "ditampilkan" else "disembunyikan").append("\n")
            append("\nBuka chat baru untuk melihat hasilnya.")
        }, isUser = false)
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (!text.contains("berita ini", true)) {
            pendingArticleTitle = ""
            pendingArticleLink = ""
        }
        val newsShortcode = parseNewsShortcode(text)
        if (newsShortcode != null) {
            input.setText("")
            clearPendingImage()
            val immSc = getSystemService(InputMethodManager::class.java)
            immSc.hideSoftInputFromWindow(input.windowToken, 0)
            addBubble(text, isUser = true)
            handleNewsShortcode(newsShortcode)
            return
        }
        val ytShortcode = parseYoutubeShortcode(text)
        if (ytShortcode != null) {
            input.setText("")
            clearPendingImage()
            val immYt = getSystemService(InputMethodManager::class.java)
            immYt.hideSoftInputFromWindow(input.windowToken, 0)
            addBubble(text, isUser = true)
            handleYoutubeShortcode(ytShortcode)
            return
        }
        val sectionShortcode = parseSectionShortcode(text)
        if (sectionShortcode != null) {
            input.setText("")
            clearPendingImage()
            val immSec = getSystemService(InputMethodManager::class.java)
            immSec.hideSoftInputFromWindow(input.windowToken, 0)
            addBubble(text, isUser = true)
            handleSectionShortcode(sectionShortcode)
            return
        }
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
            openSettingsTab()
            return
        }
        input.setText("")
        clearPendingImage()
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(input.windowToken, 0)

        val caption = (scanLabelFor(pendingScanMode)?.let { "$it: " } ?: "") + if (text.isEmpty()) "(lihat gambar)" else text
        addBubble(caption, isUser = true, imagePath = imagePath)
        // Fitur #6: masukkan instruksi pemindaian ke content pesan AI
        val finalText = if (pendingScanMode != null) {
            (if (text.isEmpty()) "" else "$text\n\n") + (scanInstructionFor(pendingScanMode) ?: text)
        } else text
        val userMsg = JSONObject().put("role", "user").put("content", finalText)
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
            var shopeeHistoryMsg: JSONObject? = null
            var cardOnly = false
            try {
                val msgs = JSONArray()
                val ass = assistant.optString("prompt")
                var systemText = ass
                if (pendingArticleLink.isNotBlank()) {
                    val articleTitle = pendingArticleTitle
                    val articleLink = pendingArticleLink
                    pendingArticleTitle = ""
                    pendingArticleLink = ""
                    val body = try {
                        NewsFeed.fetchArticleText(articleLink)
                    } catch (e: Exception) {
                        ""
                    }
                    if (body.isNotBlank()) {
                        systemText += "\n\nARTIKEL YANG SEDANG DIBAHAS: \"$articleTitle\"\n" +
                            "ISI ARTIKEL (sumber: $articleLink):\n$body\n" +
                            "Gunakan isi artikel ini sebagai dasar analisis profesional dan sebutkan sumbernya."
                    }
                }
                val profileCtx = profileContext()
                if (profileCtx.isNotEmpty()) {
                    systemText = if (systemText.isEmpty()) profileCtx else systemText + "\n\n" + profileCtx
                }
                // Fitur #12: konteks perencana hari
                plannerContext?.let { planCtx ->
                    systemText = if (systemText.isEmpty()) planCtx else systemText + "\n\n" + planCtx
                    pendingPlanParse = true
                    plannerContext = null
                }
                val webSearchInstruction = "Jika pertanyaan memerlukan info terbaru, berita, atau data terkini, gunakan pencarian web sebelum menjawab. " +
                    "Jika pengguna mencari atau ingin membeli produk barang (harga, rekomendasi belanja), SELALU gunakan tool market_search untuk mencari produk di marketplace " +
                    "(Shopee, Tokopedia, Blibli, atau Lazada - sesuai yang diminta pengguna; jika tidak disebut, biarkan parameter marketplace kosong supaya semua marketplace dicari). Hasil pencarian otomatis menjadi kartu produk dan otomatis tersimpan ke Papan Hasil AI. " +
                    "Jangan jawab hanya dengan keyword/kata kunci, dan jangan pernah mengatakan kartu produk sudah tampil bila kamu belum benar-benar memanggil tool-nya. " +
                    "Jika pengguna mencari tempat atau lokasi terdekat (dokter gigi, klinik, rumah sakit, apotek, dan sejenisnya), SELALU gunakan tool nearby_places agar 5 lokasi terdekat ditampilkan otomatis sebagai kartu lokasi di chat, lengkap dengan alamat, jarak, rating, dan tombol membuka Google Maps. " +
                    "Jangan menebak alamat atau lokasi dari pengetahuanmu sendiri untuk permintaan 'terdekat' — pakai tool-nya. " +
                    "Jika pengguna hanya bertanya lanjutan tentang tempat yang sudah ditampilkan (misalnya 'tips bertanya ke dokter tersebut', 'bagaimana cara konsultasi', atau pertanyaan serupa), jawab langsung dengan teks biasa dan JANGAN memanggil nearby_places lagi. " +
                    "Jawaban kamu selalu berupa teks biasa - jangan sisipkan gambar, tabel besar, atau tautan markdown. Gambar produk hanya sudah ditampilkan otomatis di kartu hasil pencarian. " +
                    "JANGAN gunakan market_search untuk pertanyaan berupa solusi, strategi, arah bisnis, balasan komunikasi, copywriting, caption, atau rekomendasi kata/kalimat — jawab langsung dengan teks. Kata 'market' atau 'pasar' dalam konteks arah bisnis BUKAN permintaan belanja produk."
                if (systemText.isNotEmpty() && !systemText.contains("pencarian web", ignoreCase = true)) {
                    systemText = systemText + "\n\n" + webSearchInstruction
                }
                if (systemText.isNotEmpty()) msgs.put(JSONObject().put("role", "system").put("content", systemText))
                for (i in 0 until history.length()) {
                    val m = history.getJSONObject(i)
                    val role = m.optString("role").ifEmpty { "user" }
                    val raw = m.opt("content")
                    if (raw == null) continue
                    msgs.put(JSONObject().put("role", role).put("content", raw))
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
                val marketParams = JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("keyword", JSONObject().put("type", "string").put("description", "Nama produk yang dicari pengguna, mis: sandal laki laki."))
                        .put("marketplace", JSONObject().put("type", "string")
                            .put("enum", JSONArray().put("shopee").put("tokopedia").put("blibli").put("lazada"))
                            .put("description", "Marketplace tujuan. BIARKAN KOSONG jika pengguna tidak menyebut marketplace, maka Shopee + Tokopedia + Lazada dicari sekaligus.")))
                    .put("required", JSONArray().put("keyword"))
                val marketTools = JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", "market_search")
                        .put("description", "Mencari produk di marketplace Indonesia (Shopee, Tokopedia, Blibli, Lazada) dan menampilkan produk terbaik sebagai saran di chat. Hasil otomatis tersimpan di Papan Hasil AI.")
                        .put("parameters", marketParams))
                val nearbyParams = JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("query", JSONObject().put("type", "string")
                            .put("description", "Jenis tempat yang dicari, misal: dokter gigi, klinik gigi, apotek, rumah sakit.")))
                    .put("required", JSONArray().put("query"))
                val nearbyTools = JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", "nearby_places")
                        .put("description", "Mencari tempat/lokasi terdekat di Google Maps berdasarkan lokasi pengguna saat ini dan menampilkan 5 hasil terdekat sebagai kartu lokasi di chat (nama, alamat, jarak, rating, dan tombol buka Google Maps).")
                        .put("parameters", nearbyParams))
                val foodParams = JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("query", JSONObject().put("type", "string")
                            .put("description", "Jenis makanan atau nama restoran yang dicari, misal: ayam geprek, kopi, seafood.")))
                    .put("required", JSONArray().put("query"))
                val foodTools = JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", "food_delivery")
                        .put("description", "Mencari 5 restoran atau tempat makan terdekat di Google Maps untuk dipesan lewat aplikasi food delivery yang terpasang di HP (Gojek, Grab, ShopeeFood, foodpanda).")
                        .put("parameters", foodParams))
                val toolsArray = JSONArray().put(webTools).put(marketTools).put(nearbyTools).put(foodTools)
                val lastUserText = msgs.getJSONObject(msgs.length() - 1).optString("content")
                // Marketplace yang disebut di pesan; null = tidak disebut -> cari di semua.
                val marketFromText: Market? = Market.values()
                    .firstOrNull { lastUserText.contains(it.label, true) }
                // Intent mencari/beli produk -> paksa tool market_search dipanggil.
                val hasSearchVerb = Regex("(?i)\\b(cari|carikan|cariin|beli|belikan|butuh|rekomendasi|rekomendasikan|pilihkan|saranin)\\b")
                    .containsMatchIn(lastUserText)
                val isInfoQuery = Regex("(?i)\\b(berita|informasi|artikel|resep|jadwal|lokasi|cuaca|sejarah|makna|pengertian|tutorial|lowongan|harga emas|saham)\\b")
                    .containsMatchIn(lastUserText)
                val placesNoun = Regex("(?i)(dokter gigi|klinik gigi|klinik|dokter|rumah sakit|\\brs\\b|apotek|puskesmas|pom bensin|masjid|hotel|restoran|sekolah|bank)")
                    .containsMatchIn(lastUserText)
                val nearbyIntent = Regex("(?i)\\b(terdekat|dekat|sekitar|nearby|near|cari|carikan|cariin|tampilkan|tunjukkan|daftar|list|rekomendasi|dimana)\\b")
                    .containsMatchIn(lastUserText)
                val followUpAsk = Regex("(?i)\\b(tips|saran|cara|bagaimana|gimana|kenapa|mengapa|sebaiknya|harus|tanya|tanyakan|konsultasi|pertanyaan|langkah|obat|minum|biaya|harga|jadwal)\\b")
                    .containsMatchIn(lastUserText)
                val forceNearby = nearbyIntent && placesNoun && !followUpAsk
                val forceFood = !forceNearby && Regex("(?i)(go ?food|grab ?food|shopeefood|foodpanda|pesan makan|makanan|makan|kuliner|restoran|warung|lapar|food delivery|delivery|ayam|kopi|seafood|bakso|pizza|burger)").containsMatchIn(lastUserText)
                val isArticleAsk = lastUserText.contains("berita ini", true)
                val isAdviceAsk = Regex("(?i)\\b(solusi|strategi|arah|balasan|komunikasi|pendekatan|copywriting|caption|konten|ucapan|kalimat|pesan|email|cs|customer service|follow ?up|negosiasi|pitch|judul|ide|saran|tips|bisnis|marketing)\\b").containsMatchIn(lastUserText)
                val forceMarket = !forceNearby && !forceFood && !isArticleAsk && !isAdviceAsk && ((hasSearchVerb && !isInfoQuery) ||
                        Regex("(?i)shopee|tokopedia|blibli|lazada|marketplace|jaket|koko|baju|sepatu|celana|kemeja|hoodie|tshirt|pakaian|kaos|polo|tas|dompet|power|charger|iphone|samsung|tablet|laptop|earphone|headphone|speaker|celengan|mantel|sandal|sendal|helm|kacamata|parfum|gamis|hijab|mukena|sarung|peci|topi|koper|kamera|mouse|keyboard|komputer|monitor|kulkas|blender|kompor|panci|sepeda|baterai|kabel|flashdisk").containsMatchIn(lastUserText))
                val forceWeb = !forceNearby && !forceFood && !forceMarket &&
                        (isArticleAsk || (!isAdviceAsk && Regex("(?i)(berita|kabar|terkini|terbaru|hari ini|update|ihsg|saham|pasar|kurs|inflasi|suku bunga|info harga)").containsMatchIn(lastUserText)))
                var activeTools = toolsArray
                var webSearchDone = false
                if (forceWeb) {
                    val newsQuery = lastUserText
                        .replace(Regex("(?i)^(jelaskan|jelaskan secara|tolong|please|beri|kasih|apa|bagaimana)\\b"), " ")
                        .replace(Regex("(?i)\\b(secara profesional|berita ini|berita|terkini|terbaru|hari ini|update)\\b"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .ifEmpty { "berita Indonesia" }
                    val items = try {
                        NewsFeed.fetchByQuery(newsQuery, 5)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (items.isNotEmpty()) {
                        val ctx = StringBuilder()
                            .append("\n\nKONTEKS BERITA TERKINI (data real-time dari Google News/ANTARA):\n")
                        items.forEachIndexed { ni, n ->
                            ctx.append(ni + 1).append(". ").append(n.title)
                                .append(" — ").append(n.source)
                                .append(" (").append(n.timeAgo).append(")\n")
                        }
                        ctx.append("Berita di atas sudah tersedia. Jangan panggil tool web_search; jawab langsung dengan gaya profesional, sebutkan sumbernya, dan jangan keluarkan format tool call.")
                        val sys = msgs.getJSONObject(0)
                        sys.put("content", sys.optString("content") + ctx.toString())
                        webSearchDone = true
                        val filtered = JSONArray()
                        for (ti in 0 until toolsArray.length()) {
                            val fn = toolsArray.optJSONObject(ti)?.optJSONObject("function")?.optString("name") ?: ""
                            if (fn != "web_search") filtered.put(toolsArray.get(ti))
                        }
                        activeTools = filtered
                    }
                }
                val toolChoice: Any = when {
                    forceNearby -> JSONObject().put("type", "function").put("function", JSONObject().put("name", "nearby_places"))
                    forceFood -> JSONObject().put("type", "function").put("function", JSONObject().put("name", "food_delivery"))
                    forceMarket -> JSONObject().put("type", "function").put("function", JSONObject().put("name", "market_search"))
                    else -> "auto"
                }
                var payload = JSONObject()
                    .put("model", model)
                    .put("messages", msgs)
                    .put("stream", true)
                    .put("max_tokens", 8000)
                    .put("tools", activeTools)
                    .put("tool_choice", toolChoice)

                var content: String? = null
                // Akumulator tool_calls streaming (datang bertahap: id, nama, lalu argumen)
                val toolIds = mutableMapOf<Int, String>()
                val toolNames = mutableMapOf<Int, StringBuilder>()
                val toolArgs = mutableMapOf<Int, StringBuilder>()
                var rounds = 0
                var cardRendered = false
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
                    val payloadBytes = payload.toString().toByteArray()
                    android.util.Log.i("SM_CHAT", "REQFULL ${payload}")
                    conn.outputStream.use { it.write(payloadBytes) }
                    if (conn.responseCode != 200) {
                        val err = conn.errorStream?.bufferedReader()?.readText()
                        android.util.Log.e("SM_CHAT", "HTTP ${conn.responseCode} err=${err?.take(400)}")
                        error = sanitizeError(parseError(err, conn.responseCode))
                    } else {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream))
                        var line: String?
                        var rawCount = 0
                        var finishReason: String? = null
                        while (reader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            rawCount++
                            if (rawCount <= 5) android.util.Log.i("SM_CHAT", "RAW[$rawCount] ${l.take(300)}")
                            if (l.contains("\"finish_reason\"")) {
                                finishReason = l.take(300)
                            }
                            if (!l.startsWith("data:")) continue
                            val data = l.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            val chunk = JSONObject(data)
                            val chunkErr = chunk.optJSONObject("error")
                            if (chunkErr != null) {
                                error = sanitizeError(chunkErr.optString("message").ifEmpty { getString(R.string.error_generic) })
                                break
                            }
                            val deltaObj = chunk
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
                                    applyMarkdown(aiBubble, sb.toString())
                                    scrollToBottom()
                                }
                            }
                            val deltaTools = deltaObj?.optJSONArray("tool_calls")
                            if (deltaTools != null) {
                                for (i in 0 until deltaTools.length()) {
                                    val tc = deltaTools.optJSONObject(i) ?: continue
                                    val idx = tc.optInt("index", i)
                                    val id = tc.optString("id")
                                    if (!id.isNullOrEmpty() && id != "null") toolIds[idx] = id
                                    val fn = tc.optJSONObject("function")
                                    if (fn != null) {
                                        val nm = fn.optString("name")
                                        if (!nm.isNullOrEmpty() && nm != "null") {
                                            toolNames.getOrPut(idx) { StringBuilder() }.append(nm)
                                        }
                                        val ar = fn.optString("arguments")
                                        if (!ar.isNullOrEmpty() && ar != "null") {
                                            toolArgs.getOrPut(idx) { StringBuilder() }.append(ar)
                                        }
                                    }
                                }
                            }
                        }
                        android.util.Log.i("SM_CHAT", "STREAM rawLines=$rawCount finish=${finishReason ?: "none"}")
                        content = sb.toString()
                    }
                    conn.disconnect()
                    android.util.Log.i("SM_CHAT", "RSP round=$rounds contentLen=${content?.length ?: -1} err=$error")
                    if (content != null) {
                        // Susun tool_calls lengkap dari potongan streaming
                        val assembled = assembleToolCalls(toolIds, toolNames, toolArgs)
                        if (assembled != null && rounds < 3) {
                            val toolMsgs = JSONArray()
                            var shopeeFound = false
                            var nearbyFound = false
                            var foodFound = false
                            var hasOtherTool = false
                            for (ci in 0 until assembled.length()) {
                                val call = assembled.optJSONObject(ci) ?: continue
                                val callId = call.optString("id").takeIf { it.isNotEmpty() } ?: "call_$ci"
                                val fnName = call.optJSONObject("function")?.optString("name") ?: ""
                                val argsStr = call.optJSONObject("function")?.optString("arguments") ?: "{}"
                                val argsJson = runCatching { JSONObject(argsStr) }.getOrDefault(JSONObject())
                                val resultText: String = when (fnName) {
                                    "market_search", "shopee_search" -> {
                                        val kw = argsJson.optString("keyword").ifEmpty {
                                            argsJson.optString("query")
                                        }
                                        val market = argsJson.optString("marketplace").lowercase().let { mp ->
                                            Market.values().firstOrNull {
                                                it.name.lowercase() == mp || it.label.equals(mp, true)
                                            }
                                        } ?: marketFromText
                                        // Tidak menyebut marketplace -> cari di semua yang bisa dimuat.
                                        val marketLabel = market?.label
                                            ?: MarketSearch.LOADABLE_MARKETS.joinToString(", ") { it.label }
                                        if (cardRendered) {
                                            // Sudah ada kartu di giliran ini → jangan cari & render lagi
                                            "Saran produk sudah ditampilkan di kartu. Gunakan satu kartu saja."
                                        } else {
                                        val products = try {
                                            val raw = if (market != null) MarketSearch.search(market, kw)
                                            else MarketSearch.searchMulti(kw)
                                            MarketSearch.sortByQuality(MarketSearch.enrich(raw))
                                        } catch (e: Exception) {
                                            emptyList()
                                        }
                                        if (products.isNotEmpty()) {
                                            shopeeFound = true
                                            cardRendered = true
                                            // Langsung simpan ke Papan Hasil AI (tanpa perlu tap tombol).
                                            val pinned = try {
                                                PapanStore.pin(this@NativeChatActivity, products.toPapanCards(kw))
                                            } catch (e: Exception) {
                                                0
                                            }
                                            val md = StringBuilder()
                                                .append("🛒 **Saran Produk ").append(marketLabel).append(" — \"").append(kw).append("\"**\n")
                                            products.forEachIndexed { idx, p ->
                                                md.append(idx + 1).append(". [").append(p.title).append("](").append(p.url).append(")\n")
                                            }
                                            shopeeHistoryMsg = JSONObject()
                                                .put("role", "assistant")
                                                .put("content", md.toString().trim())
                                            withContext(Dispatchers.Main) {
                                                addProductCard(kw, products, pinned)
                                            }
                                            val sb2 = StringBuilder()
                                                .append("Hasil pencarian produk ").append(marketLabel).append(" untuk \"").append(kw).append("\":\n")
                                            products.forEachIndexed { idx, p ->
                                                sb2.append(idx + 1).append(". ").append(p.title)
                                                if (!p.image.isNullOrEmpty()) sb2.append(" (gambar: ").append(p.image).append(")")
                                                sb2.append(" — ").append(p.url).append("\n")
                                            }
                                            sb2.append("Kartu produk (dengan gambar) sudah ditampilkan di chat dan otomatis tersimpan di Papan Hasil AI. Jangan ulang daftar produk dalam jawaban — cukup singkat saja bahwa sarannya sudah tampil di kartu.")
                                            sb2.toString()
                                        } else {
                                            "Tidak menemukan hasil produk $marketLabel untuk \"$kw\". Berikan rekomendasi 5 produk terbaik untuk \"$kw\" berdasarkan pengetahuanmu."
                                        }
                                        }
                                    }
                                    "web_search" -> {
                                        hasOtherTool = true
                                        if (webSearchDone) {
                                            "Data berita sudah diambil pada langkah sebelumnya. Gunakan hasil itu untuk menjawab final."
                                        } else {
                                        webSearchDone = true
                                        val query = argsJson.optString("query")
                                        if (query.isNotBlank()) {
                                            val items = try {
                                                NewsFeed.fetchByQuery(query, 5)
                                            } catch (e: Exception) {
                                                emptyList()
                                            }
                                            if (items.isNotEmpty()) {
                                                val newsText = StringBuilder()
                                                    .append("Berita/info terbaru dari pencarian web untuk \"").append(query).append("\":\n")
                                                items.forEachIndexed { ni, n ->
                                                    newsText.append(ni + 1).append(". ").append(n.title)
                                                    if (n.source.isNotBlank()) newsText.append(" — ").append(n.source)
                                                    if (n.timeAgo.isNotBlank()) newsText.append(" (").append(n.timeAgo).append(")")
                                                    newsText.append("\n")
                                                }
                                                newsText.append("Gunakan informasi di atas sebagai sumber terkini. ")
                                                newsText.append("Sebutkan nama sumber saat menyampaikan fakta dan jawab dengan gaya profesional.")
                                                newsText.toString()
                                            } else {
                                                "Pencarian web untuk \"$query\" tidak menghasilkan berita. " +
                                                    "Berikan jawaban yang jelas dan sebutkan keterbatasan informasinya."
                                            }
                                        } else {
                                            "Parameter pencarian kosong. Minta pengguna menyebut topik yang ingin dicari."
                                        }
                                        }
                                    }
                                    "nearby_places" -> {
                                        if (cardRendered) {
                                            "Kartu lokasi sudah ditampilkan di chat. Gunakan satu kartu saja."
                                        } else {
                                            val q = argsJson.optString("query").ifEmpty { nearbyQueryFrom(lastUserText) }
                                            val loc = currentLocation()
                                            val places = try {
                                                NearbyPlaces.search(q, loc.first, loc.second)
                                            } catch (e: Exception) {
                                                emptyList()
                                            }
                                            if (places.isNotEmpty()) {
                                                nearbyFound = true
                                                cardRendered = true
                                                val md = StringBuilder()
                                                    .append("📍 **").append(q).append(" Terdekat**\n")
                                                places.forEachIndexed { idx, p ->
                                                    md.append(idx + 1).append(". ").append(p.name)
                                                        .append(" — ").append(p.address)
                                                        .append(" (")
                                                        .append(String.format(Locale.getDefault(), "%.1f", p.distanceKm))
                                                        .append(" km)\n")
                                                }
                                                shopeeHistoryMsg = JSONObject()
                                                    .put("role", "assistant")
                                                    .put("content", md.toString().trim())
                                                withContext(Dispatchers.Main) {
                                                    addNearbyCard(q, places, loc)
                                                }
                                                "Lokasi $q terdekat sudah ditampilkan sebagai kartu lokasi di chat, lengkap dengan alamat, jarak, rating, dan tombol buka Google Maps. Jangan ulang daftar lokasi dalam jawabanmu — cukup singkat saja."
                                            } else {
                                                "Tidak menemukan data lokasi $q dari Google Maps. Berikan saran umum singkat."
                                            }
                                        }
                                    }
                                    "food_delivery" -> {
                                        if (cardRendered) {
                                            "Kartu makanan sudah ditampilkan di chat. Gunakan satu kartu saja."
                                        } else {
                                            val kw = argsJson.optString("query").ifEmpty { foodQueryFrom(lastUserText) }
                                            val loc = currentLocation()
                                            val places = try {
                                                NearbyPlaces.search("restoran $kw", loc.first, loc.second)
                                            } catch (e: Exception) {
                                                emptyList()
                                            }
                                            if (places.isNotEmpty()) {
                                                foodFound = true
                                                cardRendered = true
                                                val apps = FoodApps.installed(this@NativeChatActivity)
                                                val city = cityFromAddress(places.first().address)
                                                val md = StringBuilder()
                                                    .append("🍜 **Tempat Makan · area ").append(city).append(" — \"")
                                                    .append(kw).append("**\n")
                                                places.forEachIndexed { idx, p ->
                                                    md.append(idx + 1).append(". ").append(p.name)
                                                        .append(" — ").append(p.address)
                                                        .append(" (")
                                                        .append(String.format(Locale.getDefault(), "%.1f", p.distanceKm))
                                                        .append(" km)\n")
                                                }
                                                shopeeHistoryMsg = JSONObject()
                                                    .put("role", "assistant")
                                                    .put("content", md.toString().trim())
                                                withContext(Dispatchers.Main) {
                                                    addNearbyCard(
                                                        kw, places, loc,
                                                        foodMode = true,
                                                        city = city,
                                                        apps = apps
                                                    )
                                                }
                                                "5 tempat makan di area $city untuk \"$kw\" sudah ditampilkan sebagai kartu lokasi terdekat (lengkap dengan jarak, alamat, jam buka, dan tombol pesan/rute). Jangan ulang daftar di jawabanmu — cukup singkat saja."
                                            } else {
                                                "Tidak menemukan tempat makan terdekat untuk \"$kw\". Berikan saran umum singkat."
                                            }
                                        }
                                    }
                                    else -> {
                                        hasOtherTool = true
                                        "Tool \"$fnName\" tidak tersedia."
                                    }
                                }
                                toolMsgs.put(
                                    JSONObject()
                                        .put("role", "tool")
                                        .put("tool_call_id", callId)
                                        .put("content", resultText)
                                )
                            }
                            // Pencarian Shopee sukses → tampilkan kartu saja (tanpa bubble teks tambahan)
                            if ((shopeeFound || nearbyFound) && !hasOtherTool && fnAllCards(assembled)) {
                                cardOnly = sb.isEmpty()
                                break
                            }
                            val assistantMsg = JSONObject()
                                .put("role", "assistant")
                                .put("content", JSONObject.NULL)
                                .put("tool_calls", assembled)
                            msgs.put(msgs.length(), assistantMsg)
                            for (ti in 0 until toolMsgs.length()) msgs.put(msgs.length(), toolMsgs.getJSONObject(ti))
                            payload = JSONObject()
                                .put("model", model)
                                .put("messages", msgs)
                                .put("stream", true)
                                .put("max_tokens", 8000)
                                .put("tools", activeTools)
                                .put("tool_choice", "auto")
                            content = null
                            toolIds.clear(); toolNames.clear(); toolArgs.clear()
                            continue
                        }
                        // Tanpa tool call: kalau jawabannya kosong, coba lagi tanpa tools
                        if (content.isEmpty() && error == null && rounds < 3) {
                            android.util.Log.i("SM_CHAT", "EMPTY round=$rounds, retry tanpa tools")
                            payload = JSONObject()
                                .put("model", model)
                                .put("messages", msgs)
                                .put("stream", true)
                                .put("max_tokens", 8000)
                            content = null
                            toolIds.clear(); toolNames.clear(); toolArgs.clear()
                            continue
                        }
                        break
                    }
                    if (rounds == 1 && error != null && Regex("(?i)tool|function|model_no_tool").containsMatchIn(error)) {
                        payload = JSONObject()
                            .put("model", model)
                            .put("messages", msgs)
                            .put("stream", true)
                            .put("max_tokens", 8000)
                        continue
                    }
                    break
                }

                // Paksa pencarian produk bila intent produk terdeteksi tetapi model tidak memanggil tool
                if (forceMarket && !isAdviceAsk && !cardRendered && error == null) {
                    val kwDirect = lastUserText
                        .replace(Regex("(?i)\\b(cari|carikan|beli|belikan|tolong|mohon|shopee|tokopedia|blibli|lazada|marketplace|di|yang|untuk|dong|ya|please|search|find|produk|barang|solusi|strategi|arah|balasan|komunikasi|copywriting)\\b"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .ifEmpty { lastUserText }
                    val productsDirect = try {
                        val raw = if (marketFromText != null) MarketSearch.search(marketFromText, kwDirect)
                        else MarketSearch.searchMulti(kwDirect)
                        MarketSearch.sortByQuality(MarketSearch.enrich(raw))
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (productsDirect.isNotEmpty()) {
                        cardRendered = true
                        cardOnly = sb.isEmpty()
                        // Langsung simpan ke Papan Hasil AI (tanpa perlu tap tombol).
                        val pinnedDirect = try {
                            PapanStore.pin(this@NativeChatActivity, productsDirect.toPapanCards(kwDirect))
                        } catch (e: Exception) {
                            0
                        }
                        val labelDirect = marketFromText?.label
                            ?: MarketSearch.LOADABLE_MARKETS.joinToString(", ") { it.label }
                        val mdDirect = StringBuilder()
                            .append("🛒 **Saran Produk ").append(labelDirect).append(" - \"").append(kwDirect).append("\"**\n")
                        productsDirect.forEachIndexed { idx, p ->
                            mdDirect.append(idx + 1).append(". [").append(p.title).append("](").append(p.url).append(")\n")
                        }
                        shopeeHistoryMsg = JSONObject().put("role", "assistant").put("content", mdDirect.toString().trim())
                        withContext(Dispatchers.Main) { addProductCard(kwDirect, productsDirect, pinnedDirect) }
                    }
                }

                // Paksa pencarian makanan bila intent food delivery terdeteksi tetapi model tidak memanggil tool
                if (forceFood && !cardRendered) {
                    val kwFood = foodQueryFrom(lastUserText)
                    val locFood = currentLocation()
                    val foodPlaces = try {
                        NearbyPlaces.search("restoran $kwFood", locFood.first, locFood.second)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (foodPlaces.isNotEmpty()) {
                        cardRendered = true
                        cardOnly = sb.isEmpty()
                        error = null
                        val apps = FoodApps.installed(this@NativeChatActivity)
                        val city = cityFromAddress(foodPlaces.first().address)
                        val mdFood = StringBuilder()
                            .append("🍜 **Tempat Makan · area ").append(city).append(" — \"")
                            .append(kwFood).append("**\n")
                        foodPlaces.forEachIndexed { idx, p ->
                            mdFood.append(idx + 1).append(". ").append(p.name)
                                .append(" — ").append(p.address)
                                .append(" (")
                                .append(String.format(Locale.getDefault(), "%.1f", p.distanceKm))
                                .append(" km)\n")
                        }
                        shopeeHistoryMsg = JSONObject().put("role", "assistant").put("content", mdFood.toString().trim())
                        withContext(Dispatchers.Main) {
                            addNearbyCard(
                                kwFood, foodPlaces, locFood,
                                foodMode = true,
                                city = city,
                                apps = apps
                            )
                        }
                    }
                }

                // Paksa pencarian lokasi bila intent "terdekat" terdeteksi tetapi model tidak memanggil tool
                if (forceNearby && !cardRendered) {
                    val qDirect = nearbyQueryFrom(lastUserText)
                    val locDirect = currentLocation()
                    val placesDirect = try {
                        NearbyPlaces.search(qDirect, locDirect.first, locDirect.second)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (placesDirect.isNotEmpty()) {
                        cardRendered = true
                        cardOnly = sb.isEmpty()
                        error = null
                        val mdDirect = StringBuilder().append("📍 **").append(qDirect).append(" Terdekat**\n")
                        placesDirect.forEachIndexed { idx, p ->
                            mdDirect.append(idx + 1).append(". ").append(p.name)
                                .append(" — ").append(p.address)
                                .append(" (")
                                .append(String.format(Locale.getDefault(), "%.1f", p.distanceKm))
                                .append(" km)\n")
                        }
                        shopeeHistoryMsg = JSONObject().put("role", "assistant").put("content", mdDirect.toString().trim())
                        withContext(Dispatchers.Main) { addNearbyCard(qDirect, placesDirect, locDirect) }
                    }
                }
            } catch (e: Exception) {
                if (sb.isEmpty()) error = friendlyError(e)
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
                } else if (cardOnly) {
                    if (sb.isNotEmpty()) {
                        applyMarkdown(aiBubble, sb.toString())
                        history.put(JSONObject().put("role", "assistant").put("content", sb.toString()))
                    } else {
                        aiBubble.visibility = View.GONE
                        aiBubble.parent?.let { parent ->
                            (parent.parent as? View)?.visibility = View.GONE
                        }
                    }
                    shopeeHistoryMsg?.let { history.put(it) }
                    saveCurrentSession()
                } else if (sb.isNotEmpty()) {
                    applyMarkdown(aiBubble, sb.toString())
                    shopeeHistoryMsg?.let { history.put(it) }
                    history.put(
                        JSONObject().put("role", "assistant").put("content", sb.toString())
                    )
                    saveCurrentSession()
                    showPromptAdvice(text)
                    // Fitur #5: bacakan balasan dengan suara jika aktif
                    speakIfEnabled(sb.toString())
                    // Fitur #12: parsing jadwal dari balasan AI
                    if (pendingPlanParse) {
                        pendingPlanParse = false
                        val added = parsePlanAndAddTasks(sb.toString())
                        if (added.isNotEmpty()) {
                            addBubble(
                                "📅 Jadwal hari ini dibuat:\n" + added.joinToString("\n") { "• $it" },
                                isUser = false
                            )
                            Toast.makeText(this@NativeChatActivity, getString(R.string.plan_added_toast), Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    aiBubble.text = getString(R.string.error_empty)
                }
            }
        }
    }

    /** True jika semua tool_calls menghasilkan kartu (produk/lokasi). */
    private fun fnAllCards(assembled: JSONArray): Boolean {
        for (i in 0 until assembled.length()) {
            val fn = assembled.optJSONObject(i)?.optJSONObject("function")?.optString("name") ?: ""
            if (fn != "market_search" && fn != "shopee_search" && fn != "nearby_places" && fn != "food_delivery") return false
        }
        return assembled.length() > 0
    }

    /** Ambil nama kota dari alamat Google Maps, mis. "Kota Malang, Jawa Timur" -> "Malang". */
    private fun cityFromAddress(address: String): String {
        Regex("""Kota\s+([^,]+)""", RegexOption.IGNORE_CASE).find(address)?.let {
            return it.groupValues[1].trim().removeSuffix(" City")
        }
        Regex("""([A-Za-z][A-Za-z\s]+?)\s+City""", RegexOption.IGNORE_CASE).find(address)?.let {
            return it.groupValues[1].trim()
        }
        return address.split(',').map { it.trim() }
            .lastOrNull { it.isNotBlank() && !Regex("""\d|Jawa|Indonesia|Timur|Barat|Utara|Selatan""").containsMatchIn(it) }
            ?: "sekitar Anda"
    }

    /** Kata kunci makanan dari kalimat user, mis. "pesan ayam geprek lewat gofood" -> "ayam geprek". */
    private fun foodQueryFrom(text: String): String {
        val cleaned = text
            .replace(Regex("(?i)\\b(cari|pesan|beli|tolong|mohon|mau|ingin|lewat|via|di|pakai|pak|pake|go ?food|grab ?food|shopeefood|foodpanda|makanan|makan|delivery|terdekat|yang|untuk|dong|ya|please|search|find)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifEmpty { "makanan siap saji" }
    }

    /** Kata kunci tempat dari kalimat user, mis. "cari dokter gigi terdekat" -> "dokter gigi". */
    private fun nearbyQueryFrom(text: String): String {
        val m = Regex("(?i)(dokter gigi|klinik gigi|klinik|dokter|rumah sakit|apotek|puskesmas|pom bensin|masjid|hotel|restoran|sekolah|bank)")
            .find(text)
        if (m != null) return m.value.lowercase(Locale.getDefault())
        return text
            .replace(Regex("(?i)\\b(cari|carikan|tolong|mohon|di|yang|untuk|dong|ya|please|search|find|terdekat|dekat|sekitar|sini|lokasi|tempat)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "dokter gigi" }
    }

    /** Lokasi terakhir yang diketahui; fallback ke Jakarta bila izin belum diberikan. */
    @SuppressLint("MissingPermission")
    private fun currentLocation(): Pair<Double, Double> {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .mapNotNull { provider ->
                        try {
                            lm.getLastKnownLocation(provider)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .maxByOrNull { it.time }
                if (best != null) return best.latitude to best.longitude
            } catch (e: Exception) {
            }
        }
        return -6.2088 to 106.8456
    }

    /** Kartu 5 lokasi terdekat dari Google Maps. Tap baris -> dibuka di Google Maps. */
    private fun addNearbyCard(
        query: String,
        places: List<NearbyPlace>,
        origin: Pair<Double, Double>,
        foodMode: Boolean = false,
        city: String = "",
        apps: List<FoodApp> = emptyList()
    ) {
        if (places.isEmpty()) return
        val pad = resources.getDimensionPixelSize(R.dimen.bubble_padding)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_bubble_ai)
            elevation = dp(1).toFloat()
            setPadding(pad, pad, pad, pad)
        }

        card.addView(TextView(this).apply {
            text = if (foodMode) "🍜 Tempat Makan · area ${city.ifBlank { "sekitar Anda" }}"
            else "📍 $query Terdekat"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.primary))
        })
        card.addView(TextView(this).apply {
            text = if (foodMode)
                "5 tempat makan terdekat di area ${city.ifBlank { "sekitar Anda" }} · sumber Google Maps, pesan via app food delivery yang terpasang"
            else "5 lokasi terdekat dari posisi Anda · ketuk untuk dibuka di Google Maps"
            textSize = 10.5f
            setTextColor(getColor(R.color.textSecondary))
            setPadding(0, dp(2), 0, dp(6))
        })

        places.forEachIndexed { idx, p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = getDrawable(R.drawable.bg_preview)
            }
            row.addView(TextView(this).apply {
                text = "${idx + 1}.  "
                textSize = 14f
                setTextColor(getColor(R.color.primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = p.name
                textSize = 13.5f
                setTextColor(getColor(R.color.textPrimary))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            col.addView(TextView(this).apply {
                text = p.address
                textSize = 10.5f
                setTextColor(getColor(R.color.textSecondary))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val metaParts = mutableListOf<String>()
            metaParts.add(
                String.format(Locale.getDefault(), "%.1f km", p.distanceKm).replace('.', ',')
            )
            if (p.rating > 0) {
                metaParts.add(
                    "★ " + String.format(Locale.getDefault(), "%.1f", p.rating).replace('.', ',')
                )
                if (p.reviews > 0) metaParts.add(MarketSearch.formatCount(p.reviews) + " ulasan")
            }
            col.addView(TextView(this).apply {
                text = metaParts.joinToString("  ·  ")
                textSize = 10.5f
                setTextColor(getColor(R.color.primary))
            })
            if (p.openText.isNotBlank()) {
                col.addView(TextView(this).apply {
                    text = p.openText
                    textSize = 10f
                    setTextColor(getColor(if (p.openNow) R.color.successText else R.color.errorText))
                    setPadding(0, dp(2), 0, 0)
                })
            }

            val chips = mutableListOf<Pair<String, () -> Unit>>()
            val routeUrl = if (p.placeId.isNotBlank()) {
                "https://www.google.com/maps/dir/?api=1&destination=" +
                    URLEncoder.encode(p.name, "UTF-8") +
                    "&destination_place_id=${p.placeId}&travelmode=driving"
            } else {
                "https://www.google.com/maps/dir/?api=1&destination=${p.lat},${p.lon}&travelmode=driving"
            }
            chips.add("Rute" to { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(routeUrl))) })
            if (foodMode) {
                apps.firstOrNull()?.let { app ->
                    chips.add("Pesan di ${app.label}" to {
                        if (!FoodApps.open(this@NativeChatActivity, app)) {
                            Toast.makeText(this@NativeChatActivity, "Gagal membuka ${app.label}", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
            if (p.phone.isNotBlank()) {
                val phone = p.phone.filter { it.isDigit() }
                chips.add("Telepon" to {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                })
            }
            if (p.whatsapp.isNotBlank()) {
                val waMsg = if (foodMode)
                    "Halo, saya ingin bertanya menu dan harga di ${p.name}."
                else "Halo, saya ingin bertanya jadwal kontrol gigi."
                val waUrl = p.whatsapp + "?text=" + URLEncoder.encode(waMsg, "UTF-8")
                chips.add("WhatsApp" to { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))) })
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, 0)
            }
            chips.forEach { (label, action) ->
                actions.addView(TextView(this).apply {
                    text = label
                    textSize = 10.5f
                    setTextColor(getColor(R.color.primary))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = getDrawable(R.drawable.bg_chip)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(6) }
                    setOnClickListener { action() }
                })
            }
            col.addView(actions)
            row.addView(col)
            row.addView(TextView(this).apply {
                text = "  ›"
                textSize = 16f
                setTextColor(getColor(R.color.primary))
            })
            row.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.mapsUrl)))
            }
            card.addView(row)
        }

        val footerActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        footerActions.addView(TextView(this).apply {
            text = "Buka pencarian di Google Maps"
            textSize = 11.5f
            setTextColor(getColor(R.color.primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = getDrawable(R.drawable.bg_chip)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val q = if (foodMode) "tempat makan $query di $city" else "$query terdekat"
                val url = "https://www.google.com/maps/search/" +
                    URLEncoder.encode(q, "UTF-8") +
                    "/@${origin.first},${origin.second},14z"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        })
        footerActions.addView(shareChip {
            buildString {
                append(if (foodMode) "🍜 Tempat makan" else "📍 Lokasi")
                append(" terdekat — \"").append(query).append("\"\n")
                places.forEachIndexed { idx, p ->
                    append(idx + 1).append(". ").append(p.name)
                        .append(" — ").append(String.format(Locale.getDefault(), "%.1f", p.distanceKm))
                        .append(" km\n").append(p.address).append("\n")
                }
                append("— dikirim dari SahabatMobile AI")
            }
        })
        card.addView(footerActions)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_premium)
            background = getDrawable(R.drawable.bg_premium_circle)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            contentDescription = getString(R.string.brand_small)
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) }
        })
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.brand_small)
            textSize = 10.5f
            letterSpacing = 0.06f
            setTextColor(getColor(R.color.primary))
            setPadding(dp(2), 0, 0, dp(4))
        })
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        column.addView(card)
        row.addView(column)
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.bubble_margin) }
        bubbleContainer.addView(row)
        scrollToBottom()
    }

    /**
     * Susun array tool_calls lengkap dari potongan streaming.
     * Streaming mengirim id → nama fungsi → argomen dalam chunk terpisah,
     * jadi harus digabung per index agar valid untuk round berikutnya.
     */
    private fun assembleToolCalls(
        ids: Map<Int, String>,
        names: Map<Int, StringBuilder>,
        args: Map<Int, StringBuilder>
    ): JSONArray? {
        val indices = (ids.keys + names.keys + args.keys).sorted()
        if (indices.isEmpty()) return null
        val out = JSONArray()
        for (idx in indices) {
            val id = ids[idx] ?: "call_$idx"
            val name = names[idx]?.toString().orEmpty()
            if (name.isEmpty() && !ids.containsKey(idx)) continue
            val arguments = args[idx]?.toString()?.takeIf { it.isNotBlank() } ?: "{}"
            out.put(
                JSONObject()
                    .put("id", id)
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", name)
                        .put("arguments", arguments))
            )
        }
        return if (out.length() > 0) out else null
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

    /**
     * Kartu "Petunjuk" hanya ditampilkan sekali (generate pertama) —
     * setelah itu tidak perlu lagi agar chat tetap bersih.
     */
    private fun showPromptAdvice(userText: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ADVICE_SHOWN, false)) return
        prefs.edit().putBoolean(KEY_ADVICE_SHOWN, true).apply()
        CoroutineScope(Dispatchers.IO).launch {
            val items = fetchPromptSuggestionItems(userText)
            if (items.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                for (item in items.take(2)) {
                    val tv = addBubble(
                        getString(R.string.prompt_advice_prefix) + " " + promptTitle(item),
                        isUser = false
                    )
                    // Gaya kartu tips: aksen emas, teks lebih kecil, mudah diklik
                    tv.background = getDrawable(R.drawable.bg_tip_card)
                    tv.setTextColor(getColor(R.color.textPrimary))
                    tv.textSize = 13f
                    tv.setLineSpacing(3f * resources.displayMetrics.density, 1f)
                    tv.setPadding(dp(18), dp(10), dp(14), dp(10))
                    tv.maxWidth = (resources.displayMetrics.widthPixels * 0.74).toInt()
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

    /** Pesan network yang aman: tidak pernah menampilkan host/IP/port ke pengguna. */
    private fun safeNetworkMessage(): String =
        "Koneksi internet sedang bermasalah. Cek sinyal atau Wi-Fi kamu, lalu coba lagi ya."

    private fun sanitizeError(msg: String?): String {
        val m = msg?.trim().orEmpty()
        if (m.isEmpty()) return "Sesuatu tidak berjalan semestinya. Silakan coba lagi."
        val technical = Regex(
            """(?i)(failed to connect|timed?\s?out|unknown host|network is unreachable|socket|connectexception|ioexception|https?://|www\.|\d{1,3}(\.\d{1,3}){3}|port\s+\d+|(?:error|http)\s+\d{3})"""
        )
        return if (technical.containsMatchIn(m)) safeNetworkMessage() else m.take(200)
    }

    private fun friendlyError(e: Throwable?): String {
        val raw = e?.message.orEmpty()
        val network = e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException ||
            e is java.net.UnknownHostException ||
            e is java.io.IOException ||
            Regex("""(?i)(failed to connect|timed?\s?out|unknown host|network is unreachable|socket|connect)""")
                .containsMatchIn(raw)
        return if (network) safeNetworkMessage() else sanitizeError(raw)
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
            .setPositiveButton(R.string.change_model) { _, _ -> openSettingsTab() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleMenuItem(id: Int): Boolean = when (id) {
        R.id.action_profile -> {
            startActivity(Intent(this, ProfileActivity::class.java))
            true
        }
        R.id.action_profile_web -> {
            val url = if (!apiKey.isNullOrEmpty()) {
                "https://www.sahabatmobile.com/home/prompt?apiKey=$apiKey"
            } else {
                "https://www.sahabatmobile.com/home/prompt"
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(this, "Tidak bisa membuka browser", Toast.LENGTH_SHORT).show()
            }
            true
        }
        R.id.action_pick_assistant -> {
            startActivity(Intent(this, AssistantActivity::class.java))
            true
        }
        R.id.action_pick_prompt -> {
            startActivity(Intent(this, PromptActivity::class.java))
            true
        }
        R.id.action_open_web -> {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.sahabatmobile.com")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(this, "Tidak bisa membuka browser", Toast.LENGTH_SHORT).show()
            }
            true
        }
        R.id.action_voice_call -> {
            startActivity(Intent(this, VoiceCallActivity::class.java))
            true
        }
        R.id.action_solution_camera -> {
            startActivity(Intent(this, SolutionCameraActivity::class.java))
            true
        }
        R.id.action_template -> {
            startActivity(Intent(this, TemplateActivity::class.java))
            true
        }
        R.id.action_whatsapp_reply -> {
            startActivity(Intent(this, WhatsAppReplyActivity::class.java))
            true
        }
        R.id.action_papan -> {
            startActivity(Intent(this, PapanActivity::class.java))
            true
        }
        R.id.action_watchlist -> {
            startActivity(Intent(this, WatchlistActivity::class.java))
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

    /** Fitur #12: Susun Hari Saya — kirim konteks ke AI, lalu parsing jadwalnya. */
    private fun planMyDay() {
        if (apiKey.isNullOrEmpty()) {
            askApiKey()
            return
        }
        plannerContext = buildPlanContext()
        pendingPlanParse = false
        input.setText(getString(R.string.plan_day_request))
        sendMessage()
    }

    private fun buildPlanContext(): String {
        val now = java.text.SimpleDateFormat("HH:mm").format(java.util.Date())
        val sb = StringBuilder()
        sb.append("## PERINTAH KHUSUS: SUSUN HARI SAYA 📅\n")
        sb.append("Susun jadwal hari ini untuk pengguna berdasarkan data berikut:\n")
        sb.append("Waktu sekarang: $now\n")
        val tasks = AssistantData.loadTasks(this).filter { !it.done }
        if (tasks.isNotEmpty()) {
            sb.append("Tugas yang belum selesai:\n")
            tasks.forEach {
                sb.append("- ${it.text}${if (it.at > 0L) " (jam ${it.timeLabel()})" else ""}\n")
            }
        }
        try {
            AssistantData.loadHealth(this).lastOrNull()?.let {
                sb.append("Kesehatan terakhir: ${it.summary()}\n")
            }
        } catch (_: Exception) {
        }
        val job = getSharedPreferences(PREFS, MODE_PRIVATE).getString("profile_job", null)
        if (!job.isNullOrBlank()) sb.append("Pekerjaan: $job\n")
        sb.append(
            """
ATURAN OUTPUT (WAJIB):
1. Urutkan aktivitas dari sekarang sampai malam, dengan jam yang realistis.
2. Sisipkan waktu makan siang dan istirahat singkat.
3. Setiap baris jadwal WAJIB format persis: HH:MM | kegiatan
   Contoh baris: 14:30 | Follow-up pelanggan
4. Maksimal 8 baris jadwal.
5. Tambahkan SATU kalimat pengantar singkat sebelum daftar jadwal.
"""
        )
        return sb.toString()
    }

    /** Fitur #12: parsing baris "HH:MM | kegiatan" dari balasan AI → jadikan tugas. */
    private fun parsePlanAndAddTasks(reply: String): List<String> {
        val out = mutableListOf<String>()
        val re = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*\|\s*(.+)$""")
        val existing = AssistantData.loadTasks(this)
        for (line in reply.lines()) {
            val m = re.find(line) ?: continue
            val h = m.groupValues[1].toIntOrNull() ?: continue
            val min = m.groupValues[2].toIntOrNull() ?: continue
            if (h !in 0..23 || min !in 0..59) continue
            val text = m.groupValues[3].trim().take(80)
            if (text.isEmpty()) continue
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, h)
                set(java.util.Calendar.MINUTE, min)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis() + 60_000L) {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }
            val dupe = existing.any { it.text.equals(text, ignoreCase = true) && it.at == cal.timeInMillis }
            if (dupe) continue
            existing.add(AssistantData.Task(text = text, at = cal.timeInMillis))
            out.add(String.format("%02d:%02d — %s", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), text))
        }
        if (out.isNotEmpty()) {
            AssistantData.saveTasks(this, existing)
            AssistantWidgetProvider.updateAll(this)
        }
        return out
    }

    /** Fitur #5: bacakan balasan AI dengan suara jika pengaturan aktif. */
    private fun speakIfEnabled(text: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean("tts_enabled", false)) return
        speak(text)
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        val clean = text
            .replace(Regex("[*#`_~|>\\[\\]]"), "")   // hapus karakter markdown
            .replace(Regex("\\([^)]*\\)"), "")        // hapus catatan dalam kurung
            .replace(Regex("[\\p{So}\\p{Sk}\\p{Cn}]"), " ") // hapus emoji/simbol
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isEmpty()) return
        tts?.setSpeechRate(1.05f)
        tts?.speak(clean.take(600), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "sm_reply")
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
        // Fitur #5: matikan TTS
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        super.onDestroy()
    }
}




