package com.sahabatmobile.chat

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Satu layar untuk dua hal sekaligus: Profil pengguna + Pengaturan aplikasi.
 * Tujuannya: menu ⋮ tidak lagi memuat banyak item pengaturan, semuanya dikumpulkan di sini.
 */
class ProfileActivity : AppCompatActivity() {

    companion object {
        /** Buka langsung ke tab Pengaturan (dipakai dari pil model di layar chat). */
        const val EXTRA_TAB_SETTINGS = "open_settings_tab"

        /** Buka langsung ke tab Tugas & Pengingat. */
        const val EXTRA_TAB_TASKS = "open_tasks_tab"

        private val MONTH_NAMES = arrayOf(
            "Januari", "Februari", "Maret", "April", "Mei", "Juni",
            "Juli", "Agustus", "September", "Oktober", "November", "Desember"
        )
    }

    private lateinit var txtBirthDate: TextView
    private lateinit var txtAge: TextView
    private lateinit var edtJob: EditText
    private lateinit var edtHobby: EditText
    private lateinit var edtHealth: EditText
    private lateinit var edtGoal: EditText
    private lateinit var btnSave: Button
    private lateinit var btnClear: Button

    // Pengaturan
    private lateinit var edtApiKey: EditText
    private lateinit var txtKeyStatus: TextView
    private lateinit var btnSaveKey: Button
    private lateinit var txtCurrentModel: TextView
    private lateinit var btnPickModel: Button
    private lateinit var switchWide: SwitchCompat
    private lateinit var switchWaAuto: SwitchCompat
    private lateinit var switchWaTranslate: SwitchCompat
    private lateinit var btnEnableWaNotifications: Button
    private lateinit var spinnerWaPersonal: Spinner
    private lateinit var spinnerWaBusiness: Spinner
    private lateinit var spinnerWaDefault: Spinner
    private lateinit var edtProductInfo: EditText
    private lateinit var btnSaveProduct: Button
    private lateinit var edtFaqInfo: EditText
    private lateinit var btnSaveFaq: Button
    private lateinit var switchDailySummary: androidx.appcompat.widget.SwitchCompat
    private lateinit var btnPickSummaryTime: Button
    private lateinit var btnTestSummary: Button
    private lateinit var btnTestWeekly: Button
    private lateinit var btnSchedAdd: Button
    private lateinit var btnSchedManage: Button
    private lateinit var btnContactAdd: Button
    private lateinit var btnContactManage: Button
    private lateinit var switchTts: SwitchCompat
    private lateinit var spinnerFinType: android.widget.Spinner
    private lateinit var edtFinAmount: EditText
    private lateinit var edtFinDesc: EditText
    private lateinit var btnFinAdd: Button
    private lateinit var tvFinTotals: TextView
    private lateinit var switchFocus: SwitchCompat
    private lateinit var edtFocusReply: EditText
    private lateinit var tvFocusStatus: TextView
    private lateinit var btnBackup: Button
    private lateinit var btnRestore: Button
    private lateinit var btnUpdate: Button

    // Fitur #10: Backup & Export
    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeBackup(uri) }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) readBackup(uri) }
    private lateinit var btnClearHistory: Button

    // Tab
    private lateinit var tabProfile: TextView
    private lateinit var tabSettings: TextView
    private lateinit var tabTasks: TextView
    private lateinit var scrollProfile: View
    private lateinit var scrollSettings: View
    private lateinit var scrollTasks: View

    // Tugas & catatan kesehatan
    private lateinit var btnAddTask: Button
    private lateinit var taskList: LinearLayout
    private lateinit var txtTaskEmpty: TextView
    private lateinit var healthList: LinearLayout
    private lateinit var txtHealthEmpty: TextView

    private var birthDateMillis: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        bindViews()
        setupTabs()
        if (intent?.getBooleanExtra(EXTRA_TAB_SETTINGS, false) == true) {
            showTab(Tab.SETTINGS)
        }
        if (intent?.getBooleanExtra(EXTRA_TAB_TASKS, false) == true) {
            showTab(Tab.TASKS)
        }

        loadProfile()
        loadSettings()
        refreshFinanceSummary()

        btnSave.setOnClickListener { saveProfile() }
        btnClear.setOnClickListener { clearProfile() }
        findViewById<Button>(R.id.btnPickDate).setOnClickListener { pickDate() }
        findViewById<TextView>(R.id.txtBirthDate).setOnClickListener { pickDate() }
        btnSaveKey.setOnClickListener { saveApiKey() }
        btnPickModel.setOnClickListener { pickModel() }
        btnClearHistory.setOnClickListener { clearHistory() }
        btnAddTask.setOnClickListener { addTaskDialog() }
        switchWide.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE).edit()
                .putBoolean(NativeChatActivity.KEY_WIDE, checked).apply()
        }
        switchWaAuto.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE).edit()
                .putBoolean(NativeChatActivity.KEY_WA_AUTO, checked).apply()
        }
        switchWaTranslate.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE).edit()
                .putBoolean("wa_auto_translate", checked).apply()
            Toast.makeText(
                this,
                if (checked) "Auto-terjemahan aktif 🌍" else "Auto-terjemahan mati",
                Toast.LENGTH_SHORT
            ).show()
        }
        btnEnableWaNotifications.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Tidak bisa membuka pengaturan notifikasi", Toast.LENGTH_SHORT).show()
            }
        }

        // Setup WhatsApp Number Mapping spinners
        val waModeOptions = arrayOf("Personal / Curhat", "Sales / Jualan", "Campuran (AI deteksi)")
        val waModeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, waModeOptions)
        spinnerWaPersonal.adapter = waModeAdapter
        spinnerWaBusiness.adapter = waModeAdapter

        val waDefaultOptions = arrayOf("🌐 Keduanya (tanpa filter)", "📱 Personal", "💼 Business")
        val waDefaultAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, waDefaultOptions)
        spinnerWaDefault.adapter = waDefaultAdapter

        // Load saved preferences
        val p = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
        spinnerWaPersonal.setSelection(p.getInt("wa_personal_mode", 0))
        spinnerWaBusiness.setSelection(p.getInt("wa_business_mode", 1))
        spinnerWaDefault.setSelection(p.getInt("wa_default_number", 0))

        // Save on change
        spinnerWaPersonal.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                p.edit().putInt("wa_personal_mode", position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerWaBusiness.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                p.edit().putInt("wa_business_mode", position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerWaDefault.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                p.edit().putInt("wa_default_number", position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Load product & FAQ info
        edtProductInfo.setText(p.getString("wa_product_info", ""))
        edtFaqInfo.setText(p.getString("wa_faq_info", ""))

        btnSaveProduct.setOnClickListener {
            p.edit().putString("wa_product_info", edtProductInfo.text.toString()).apply()
            Toast.makeText(this, "Info produk disimpan!", Toast.LENGTH_SHORT).show()
        }
        btnSaveFaq.setOnClickListener {
            p.edit().putString("wa_faq_info", edtFaqInfo.text.toString()).apply()
            Toast.makeText(this, "FAQ disimpan!", Toast.LENGTH_SHORT).show()
        }

        // Ringkasan Harian
        switchDailySummary.isChecked = p.getBoolean("daily_summary_enabled", true)
        switchDailySummary.setOnCheckedChangeListener { _, checked ->
            p.edit().putBoolean("daily_summary_enabled", checked).apply()
            DailySummaryReceiver.schedule(this)
            Toast.makeText(
                this,
                if (checked) "Ringkasan harian aktif!" else "Ringkasan harian dimatikan",
                Toast.LENGTH_SHORT
            ).show()
        }
        updateSummaryTimeLabel(p)
        btnPickSummaryTime.setOnClickListener {
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, p.getInt("daily_summary_hour", 7))
                set(java.util.Calendar.MINUTE, p.getInt("daily_summary_minute", 0))
            }
            TimePickerDialog(this, { _, h, m ->
                p.edit().putInt("daily_summary_hour", h).putInt("daily_summary_minute", m).apply()
                updateSummaryTimeLabel(p)
                DailySummaryReceiver.schedule(this)
                Toast.makeText(this, "Waktu ringkasan diubah!", Toast.LENGTH_SHORT).show()
            }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true)
                .show()
        }
        btnTestSummary.setOnClickListener {
            DailySummaryReceiver().testNow(this)
            Toast.makeText(this, "Ringkasan dikirim! Lihat notifikasi ☀️", Toast.LENGTH_SHORT).show()
        }
        btnTestWeekly.setOnClickListener {
            WeeklySummaryReceiver().testNow(this)
            Toast.makeText(this, "Ringkasan mingguan dikirim! Lihat notifikasi 📊", Toast.LENGTH_SHORT).show()
        }
        // Fitur #3: Pesan Terjadwal WA
        btnSchedAdd.setOnClickListener { showAddScheduledDialog() }
        btnSchedManage.setOnClickListener { showManageScheduledDialog() }
        // Fitur #4: Persona per Kontak
        btnContactAdd.setOnClickListener { showAddContactPersonaDialog() }
        btnContactManage.setOnClickListener { showManageContactPersonaDialog() }
        // Fitur #5: Suara AI (TTS)
        switchTts.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE).edit()
                .putBoolean("tts_enabled", checked).apply()
            Toast.makeText(
                this,
                if (checked) "Suara AI aktif 🔊" else "Suara AI mati",
                Toast.LENGTH_SHORT
            ).show()
        }
        // Fitur #7: Catatan Keuangan
        spinnerFinType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Pengeluaran", "Pemasukan")
        )
        spinnerFinType.setSelection(0)
        btnFinAdd.setOnClickListener {
            val type = if (spinnerFinType.selectedItemPosition == 0) "expense" else "income"
            val amount = edtFinAmount.text.toString().toDoubleOrNull() ?: 0.0
            val desc = edtFinDesc.text.toString().trim()
            if (amount <= 0.0) {
                Toast.makeText(this, "Nominal harus > 0", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val list = AssistantData.loadFin(this).toMutableList()
            list.add(AssistantData.FinEntry(type = type, amount = amount, desc = desc.ifEmpty { if (type == "expense") "Pengeluaran" else "Pemasukan" }))
            AssistantData.saveFin(this, list)
            edtFinAmount.setText("")
            edtFinDesc.setText("")
            refreshFinanceSummary()
            AssistantWidgetProvider.updateAll(this)
            Toast.makeText(this, "Tercatat! 💰", Toast.LENGTH_SHORT).show()
        }

        // Fitur #9: Mode Fokus
        edtFocusReply.setText(p.getString("focus_reply", getString(R.string.focus_default_reply)))
        edtFocusReply.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                p.edit().putString("focus_reply", s?.toString() ?: "").apply()
            }
        })
        switchFocus.isChecked =
            p.getBoolean("focus_mode", false) && p.getLong("focus_until", 0L) > System.currentTimeMillis()
        switchFocus.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val options = arrayOf("30 menit", "60 menit", "90 menit", "120 menit")
                val minutes = intArrayOf(30, 60, 90, 120)
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("🌙 Mode Fokus berapa lama?")
                    .setItems(options) { _, which ->
                        val until = System.currentTimeMillis() + minutes[which] * 60_000L
                        p.edit().putBoolean("focus_mode", true).putLong("focus_until", until).apply()
                        updateFocusStatus()
                        val t = java.text.SimpleDateFormat("HH:mm").format(java.util.Date(until))
                        Toast.makeText(this, "Mode fokus aktif sampai $t 🌙", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Batal") { _, _ -> switchFocus.isChecked = false }
                    .setOnCancelListener { switchFocus.isChecked = false }
                    .show()
            } else {
                p.edit().putBoolean("focus_mode", false).apply()
                updateFocusStatus()
                Toast.makeText(this, "Mode fokus nonaktif", Toast.LENGTH_SHORT).show()
            }
        }
        updateFocusStatus()

        // Fitur #10: Backup & Export
        btnBackup.setOnClickListener {
            val name = "sahabatmobile_backup_" +
                java.text.SimpleDateFormat("yyyyMMdd_HHmm").format(java.util.Date()) + ".json"
            try {
                exportLauncher.launch(name)
            } catch (e: Exception) {
                Toast.makeText(this, "Tidak bisa membuka penyimpan", Toast.LENGTH_SHORT).show()
            }
        }
        btnRestore.setOnClickListener {
            try {
                importLauncher.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                Toast.makeText(this, "Tidak bisa membuka penyimpan", Toast.LENGTH_SHORT).show()
            }
        }
        btnUpdate.setOnClickListener { checkUpdate() }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun bindViews() {
        txtBirthDate = findViewById(R.id.txtBirthDate)
        txtAge = findViewById(R.id.txtAge)
        edtJob = findViewById(R.id.edtJob)
        edtHobby = findViewById(R.id.edtHobby)
        edtHealth = findViewById(R.id.edtHealth)
        edtGoal = findViewById(R.id.edtGoal)
        btnSave = findViewById(R.id.btnSave)
        btnClear = findViewById(R.id.btnClear)

        edtApiKey = findViewById(R.id.edtApiKey)
        txtKeyStatus = findViewById(R.id.txtKeyStatus)
        btnSaveKey = findViewById(R.id.btnSaveKey)
        txtCurrentModel = findViewById(R.id.txtCurrentModel)
        btnPickModel = findViewById(R.id.btnPickModel)
        switchWide = findViewById(R.id.switchWide)
        switchWaAuto = findViewById(R.id.switchWaAuto)
        switchWaTranslate = findViewById(R.id.switchWaTranslate)
        btnEnableWaNotifications = findViewById(R.id.btnEnableWaNotifications)
        spinnerWaPersonal = findViewById(R.id.spinnerWaPersonal)
        spinnerWaBusiness = findViewById(R.id.spinnerWaBusiness)
        spinnerWaDefault = findViewById(R.id.spinnerWaDefault)
        edtProductInfo = findViewById(R.id.edtProductInfo)
        btnSaveProduct = findViewById(R.id.btnSaveProduct)
        edtFaqInfo = findViewById(R.id.edtFaqInfo)
        btnSaveFaq = findViewById(R.id.btnSaveFaq)
        switchDailySummary = findViewById(R.id.switchDailySummary)
        btnPickSummaryTime = findViewById(R.id.btnPickSummaryTime)
        btnTestSummary = findViewById(R.id.btnTestSummary)
        btnTestWeekly = findViewById(R.id.btnTestWeekly)
        btnSchedAdd = findViewById(R.id.btnSchedAdd)
        btnSchedManage = findViewById(R.id.btnSchedManage)
        btnContactAdd = findViewById(R.id.btnContactAdd)
        btnContactManage = findViewById(R.id.btnContactManage)
        switchTts = findViewById(R.id.switchTts)
        spinnerFinType = findViewById(R.id.spinnerFinType)
        edtFinAmount = findViewById(R.id.edtFinAmount)
        edtFinDesc = findViewById(R.id.edtFinDesc)
        btnFinAdd = findViewById(R.id.btnFinAdd)
        tvFinTotals = findViewById(R.id.tvFinTotals)
        switchFocus = findViewById(R.id.switchFocus)
        edtFocusReply = findViewById(R.id.edtFocusReply)
        tvFocusStatus = findViewById(R.id.tvFocusStatus)
        btnBackup = findViewById(R.id.btnBackup)
        btnRestore = findViewById(R.id.btnRestore)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        tabProfile = findViewById(R.id.tabProfile)
        tabSettings = findViewById(R.id.tabSettings)
        tabTasks = findViewById(R.id.tabTasks)
        scrollProfile = findViewById(R.id.scrollProfile)
        scrollSettings = findViewById(R.id.scrollSettings)
        scrollTasks = findViewById(R.id.scrollTasks)

        btnAddTask = findViewById(R.id.btnAddTask)
        taskList = findViewById(R.id.taskList)
        txtTaskEmpty = findViewById(R.id.txtTaskEmpty)
        healthList = findViewById(R.id.healthList)
        txtHealthEmpty = findViewById(R.id.txtHealthEmpty)
    }

    private fun setupTabs() {
        tabProfile.setOnClickListener { showTab(Tab.PROFILE) }
        tabSettings.setOnClickListener { showTab(Tab.SETTINGS) }
        tabTasks.setOnClickListener { showTab(Tab.TASKS) }
        showTab(Tab.PROFILE)
    }

    private enum class Tab { PROFILE, SETTINGS, TASKS }

    private fun showTab(tab: Tab) {
        scrollProfile.visibility = if (tab == Tab.PROFILE) View.VISIBLE else View.GONE
        scrollSettings.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE
        scrollTasks.visibility = if (tab == Tab.TASKS) View.VISIBLE else View.GONE

        styleTab(tabProfile, tab == Tab.PROFILE)
        styleTab(tabSettings, tab == Tab.SETTINGS)
        styleTab(tabTasks, tab == Tab.TASKS)

        if (tab == Tab.TASKS) {
            renderTasks()
            renderHealth()
        }
    }

    private fun styleTab(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected
        )
        view.setTextColor(getColor(if (selected) R.color.primary else R.color.textSecondary))
    }

    // ---------------- TUGAS & PENGINGAT ----------------

    private fun renderTasks() {
        val tasks = AssistantData.loadTasks(this)
        taskList.removeAllViews()
        txtTaskEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE

        val sorted = tasks.sortedWith(
            compareBy({ it.done }, { if (it.at > 0) it.at else Long.MAX_VALUE })
        )
        for (t in sorted) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.setPadding(dp(12), dp(12), dp(12), dp(12))
            row.setBackgroundResource(R.drawable.bg_input)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            row.layoutParams = lp

            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            val title = TextView(this)
            title.text = t.text
            title.textSize = 14.5f
            title.setTextColor(getColor(R.color.textPrimary))
            title.alpha = if (t.done) 0.45f else 1f
            box.addView(title)

            val sub = TextView(this)
            val parts = mutableListOf<String>()
            if (t.at > 0L) parts.add(getString(R.string.task_at, t.timeLabel()))
            if (t.repeatDaily) parts.add(getString(R.string.task_repeat))
            if (t.done) parts.add(getString(R.string.task_done))
            sub.text = parts.joinToString(" · ")
            sub.textSize = 11.5f
            sub.setTextColor(getColor(R.color.textSecondary))
            if (parts.isNotEmpty()) box.addView(sub)

            row.addView(
                box,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            val check = TextView(this)
            check.text = if (t.done) "☑" else "☐"
            check.textSize = 20f
            check.setTextColor(getColor(R.color.primary))
            check.setPadding(dp(10), 0, dp(10), 0)
            check.setOnClickListener {
                val list = AssistantData.loadTasks(this)
                val idx = list.indexOfFirst { it.id == t.id }
                if (idx >= 0) {
                    val cur = list[idx]
                    list[idx] = cur.copy(done = !cur.done)
                    AssistantData.saveTasks(this, list); AssistantWidgetProvider.updateAll(this)
                    renderTasks()
                }
            }
            row.addView(check)

            val del = TextView(this)
            del.text = "✕"
            del.textSize = 16f
            del.setTextColor(getColor(R.color.errorText))
            del.setPadding(dp(10), 0, dp(4), 0)
            del.setOnClickListener {
                val list = AssistantData.loadTasks(this)
                val idx = list.indexOfFirst { it.id == t.id }
                if (idx >= 0) {
                    list.removeAt(idx)
                    AssistantData.saveTasks(this, list); AssistantWidgetProvider.updateAll(this)
                    renderTasks()
                }
            }
            row.addView(del)

            taskList.addView(row)
        }
    }

    /** Dialog tambah tugas: teks, jam (opsional), ulangi harian. */
    private fun addTaskDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(10), dp(24), 0)

        val edit = EditText(this)
        edit.hint = getString(R.string.task_hint)
        edit.setSingleLine()
        box.addView(edit)

        var timeMillis = 0L
        val timeBtn = Button(this)
        timeBtn.text = getString(R.string.task_pick_time)
        timeBtn.setOnClickListener {
            val cal = Calendar.getInstance()
            TimePickerDialog(
                this,
                { _, hour, minute ->
                    val c = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    timeMillis = c.timeInMillis
                    timeBtn.text = getString(
                        R.string.task_at,
                        String.format("%02d:%02d", hour, minute)
                    )
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                true
            ).show()
        }
        box.addView(timeBtn)

        val repeat = CheckBox(this)
        repeat.text = getString(R.string.task_repeat)
        box.addView(repeat)

        AlertDialog.Builder(this)
            .setTitle(R.string.add_task)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, R.string.task_empty_input, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val list = AssistantData.loadTasks(this)
                list.add(
                    AssistantData.Task(
                        text = text,
                        at = timeMillis,
                        repeatDaily = repeat.isChecked
                    )
                )
                AssistantData.saveTasks(this, list); AssistantWidgetProvider.updateAll(this)
                renderTasks()
                Toast.makeText(this, R.string.task_added, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- CATATAN KESEHATAN ----------------

    private fun renderHealth() {
        val notes = AssistantData.loadHealth(this).sortedByDescending { it.ts }.take(10)
        healthList.removeAllViews()
        txtHealthEmpty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        for (n in notes) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(12), dp(10), dp(12), dp(10))
            row.setBackgroundResource(R.drawable.bg_input)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            row.layoutParams = lp

            val date = TextView(this)
            date.text = n.dateLabel()
            date.textSize = 11.5f
            date.setTextColor(getColor(R.color.primary))
            date.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            row.addView(date)

            val sum = TextView(this)
            sum.text = n.summary().ifEmpty { getString(R.string.health_empty) }
            sum.textSize = 13.5f
            sum.setTextColor(getColor(R.color.textPrimary))
            row.addView(sum)

            healthList.addView(row)
        }
    }

    // ---------------- PROFIL ----------------

    /**
     * SATU-SATUNYA tempat memilih tanggal lahir: tahun (roda putar, mudah),
     * bulan, dan hari sekaligus dalam satu dialog.
     */
    private fun pickDate() {
        val now = Calendar.getInstance()
        val minYear = 1900
        val maxYear = now.get(Calendar.YEAR)

        var year = maxYear
        var month = Calendar.JANUARY
        var day = 1
        if (birthDateMillis > 0L) {
            val b = Calendar.getInstance().apply { timeInMillis = birthDateMillis }
            year = b.get(Calendar.YEAR)
            month = b.get(Calendar.MONTH)
            day = b.get(Calendar.DAY_OF_MONTH)
        }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(6), dp(20), 0)

        val yearPicker = NumberPicker(this)
        yearPicker.minValue = minYear
        yearPicker.maxValue = maxYear
        yearPicker.wrapSelectorWheel = false
        yearPicker.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        yearPicker.value = year
        box.addView(yearPicker)

        val monthSpinner = Spinner(this)
        monthSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            MONTH_NAMES
        )
        monthSpinner.setSelection(month)
        box.addView(monthSpinner)

        val daySpinner = Spinner(this)
        box.addView(daySpinner)

        fun daysInMonth(y: Int, m: Int): Int =
            Calendar.getInstance().apply {
                clear()
                set(y, m, 1)
            }.getActualMaximum(Calendar.DAY_OF_MONTH)

        fun refreshDays() {
            val max = daysInMonth(yearPicker.value, monthSpinner.selectedItemPosition)
            val days = ArrayList<String>(max)
            for (d in 1..max) days.add(d.toString())
            daySpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                days
            )
            daySpinner.setSelection((day - 1).coerceIn(0, max - 1), false)
        }

        yearPicker.setOnValueChangedListener { _, _, newVal ->
            year = newVal
            refreshDays()
        }
        monthSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                month = position
                refreshDays()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refreshDays()

        AlertDialog.Builder(this)
            .setTitle(R.string.pick_birth_date)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val selYear = yearPicker.value
                val selMonth = monthSpinner.selectedItemPosition
                val selDay = (daySpinner.selectedItemPosition + 1)
                    .coerceAtMost(daysInMonth(selYear, selMonth))
                val c = Calendar.getInstance()
                c.set(Calendar.YEAR, selYear)
                c.set(Calendar.MONTH, selMonth)
                c.set(Calendar.DAY_OF_MONTH, selDay)
                c.set(Calendar.HOUR_OF_DAY, 0)
                c.set(Calendar.MINUTE, 0)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                birthDateMillis = c.timeInMillis
                updateBirthDateText()
                updateAge()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateBirthDateText() {
        if (birthDateMillis == 0L) {
            txtBirthDate.text = getString(R.string.birth_date_empty)
        } else {
            txtBirthDate.text =
                SimpleDateFormat("dd MMMM yyyy", Locale("id", "ID")).format(birthDateMillis)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun computeAge(): Int {
        if (birthDateMillis == 0L) return 0
        val birth = Calendar.getInstance().apply { timeInMillis = birthDateMillis }
        val now = Calendar.getInstance()
        var age = now.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
        if (now.get(Calendar.DAY_OF_YEAR) < birth.get(Calendar.DAY_OF_YEAR)) age--
        return age.coerceAtLeast(0)
    }

    private fun updateAge() {
        val age = computeAge()
        txtAge.text = if (age > 0) "Umur: $age tahun" else "Umur: - (isi tanggal lahir)"
    }

    private fun loadProfile() {
        val p = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
        birthDateMillis = p.getLong("profile_birth", 0L)
        edtJob.setText(p.getString("profile_job", "") ?: "")
        edtHobby.setText(p.getString("profile_hobby", "") ?: "")
        edtHealth.setText(p.getString("profile_health", "") ?: "")
        edtGoal.setText(p.getString("profile_goal", "") ?: "")
        updateBirthDateText()
        updateAge()
    }

    private fun saveProfile() {
        getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE).edit()
            .putLong("profile_birth", birthDateMillis)
            .putString("profile_job", edtJob.text.toString().trim())
            .putString("profile_hobby", edtHobby.text.toString().trim())
            .putString("profile_health", edtHealth.text.toString().trim())
            .putString("profile_goal", edtGoal.text.toString().trim())
            .apply()
        Toast.makeText(this, "Profil disimpan. AI akan membaca data Anda.", Toast.LENGTH_SHORT).show()
    }

    private fun clearProfile() {
        birthDateMillis = 0L
        edtJob.setText("")
        edtHobby.setText("")
        edtHealth.setText("")
        edtGoal.setText("")
        getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE).edit()
            .remove("profile_birth").remove("profile_job").remove("profile_hobby")
            .remove("profile_health").remove("profile_goal").apply()
        updateBirthDateText()
        updateAge()
        Toast.makeText(this, "Profil dihapus.", Toast.LENGTH_SHORT).show()
    }

    // ---------------- PENGATURAN ----------------

    private fun loadSettings() {
        val p = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
        val key = p.getString(NativeChatActivity.KEY_API, null)
        if (!key.isNullOrEmpty()) edtApiKey.setText(key)
        updateKeyStatus(p.getString(NativeChatActivity.KEY_TYPE, null))

        val model = p.getString(NativeChatActivity.KEY_MODEL, null)
            ?: NativeChatActivity.DEFAULT_MODEL
        txtCurrentModel.text = getString(R.string.model_none_selected, model)

        switchWide.isChecked = p.getBoolean(NativeChatActivity.KEY_WIDE, false)
        switchWaAuto.isChecked = p.getBoolean(NativeChatActivity.KEY_WA_AUTO, false)
        switchWaTranslate.isChecked = p.getBoolean("wa_auto_translate", true)
        switchTts.isChecked = p.getBoolean("tts_enabled", false)
    }

    private fun updateKeyStatus(type: String?) {
        txtKeyStatus.text = when (type) {
            "paket" -> getString(R.string.key_badge_paket) + " ✓"
            "payg" -> getString(R.string.key_badge_payg) + " ✓"
            "invalid" -> getString(R.string.key_type_invalid)
            else -> getString(R.string.key_not_set)
        }
        txtKeyStatus.setTextColor(
            getColor(
                when (type) {
                    "paket", "payg" -> R.color.successText
                    "invalid" -> R.color.errorText
                    else -> R.color.textSecondary
                }
            )
        )
    }

    private fun saveApiKey() {
        val key = edtApiKey.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, "API key belum diisi", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE).edit()
            .putString(NativeChatActivity.KEY_API, key)
            .remove(NativeChatActivity.KEY_TYPE)
            .apply()
        txtKeyStatus.text = getString(R.string.key_check_running)
        CoroutineScope(Dispatchers.IO).launch {
            val type = SettingsApi.probeKeyType(key)
            withContext(Dispatchers.Main) {
                getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE).edit()
                    .putString(NativeChatActivity.KEY_TYPE, type).apply()
                updateKeyStatus(type)
                Toast.makeText(this@ProfileActivity, getString(R.string.key_saved), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pickModel() {
        val key = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
            .getString(NativeChatActivity.KEY_API, null).orEmpty()
        if (key.isBlank()) {
            Toast.makeText(this, "Simpan API key dulu", Toast.LENGTH_SHORT).show()
            return
        }
        btnPickModel.isEnabled = false
        btnPickModel.text = getString(R.string.loading_models)
        CoroutineScope(Dispatchers.IO).launch {
            val models = SettingsApi.fetchModels(key)
            val saldo = SettingsApi.fetchBalanceRupiah(key)
            withContext(Dispatchers.Main) {
                btnPickModel.isEnabled = true
                btnPickModel.text = getString(R.string.change_model)
                if (models.isEmpty()) {
                    Toast.makeText(
                        this@ProfileActivity,
                        getString(R.string.models_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                showModelDialog(models, saldo)
            }
        }
    }

    /**
     * SATU-SATUNYA tempat memilih model AI di aplikasi ini.
     * Menu chat & pil model hanya menampilkan model aktif dan mengarahkan ke sini.
     */
    private fun showModelDialog(models: List<SettingsApi.Model>, saldoRupiah: Long) {
        val prefs = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
        val allowed = readAllowedModels()
        val isPayg = prefs.getBoolean(NativeChatActivity.KEY_PAYG, false)
        val plan = prefs.getString(NativeChatActivity.KEY_PLAN_NAME, null)?.ifEmpty { null }
        var showAll = prefs.getBoolean(NativeChatActivity.KEY_SHOW_ALL, false)

        fun isExclusive(m: SettingsApi.Model): Boolean {
            val id = m.id.lowercase()
            return id.startsWith("mimo") || id.startsWith("minimax")
        }

        fun isAllowed(m: SettingsApi.Model): Boolean {
            val id = m.id.lowercase()
            // Model eksklusif MiMo/MiniMax hanya untuk pelanggan paket.
            if (isPayg && isExclusive(m)) return false
            if (allowed.isNullOrEmpty()) return true
            if (m.inPlan == true) return true
            return allowed.any { e ->
                val entry = e.lowercase()
                id == entry || id.contains(entry) || entry.contains(id)
            }
        }

        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(12), dp(16), dp(8))

        val info = TextView(this)
        info.text = when {
            isPayg -> getString(R.string.access_payg)
            !plan.isNullOrEmpty() ->
                getString(R.string.access_package, plan, allowed?.size ?: 0)
            else -> getString(R.string.access_unknown)
        }
        info.setTextColor(getColor(R.color.textSecondary))
        info.textSize = 12.5f
        info.setLineSpacing(dp(3).toFloat(), 1f)
        box.addView(info)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        lateinit var dlg: AlertDialog

        // Paket user: tampilkan hanya model yang ditanggung paket. Model lain
        // dipindahkan ke bagian lipat supaya tidak menyesatkan pelanggan.
        if (showAll) {
            showAll = false
            prefs.edit().putBoolean(NativeChatActivity.KEY_SHOW_ALL, false).apply()
        }
        var showOutside = false

        fun addRow(m: SettingsApi.Model, ok: Boolean) {
            val exclusiveForPayg = !ok && isPayg && isExclusive(m)
            val row = TextView(this)
            row.text = when {
                exclusiveForPayg -> "${m.name}  (khusus paket)"
                ok -> m.name
                else -> "${m.name}  (bayar saldo)"
            }
            row.setTextColor(getColor(if (ok) R.color.textPrimary else R.color.textSecondary))
            row.textSize = 15f
            row.setPadding(dp(12), dp(14), dp(12), dp(14))
            row.setOnClickListener {
                if (exclusiveForPayg) {
                    Toast.makeText(
                        this@ProfileActivity,
                        "Model MiMo/MiniMax khusus pelanggan paket. Ambil paket untuk memakainya.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                if (!ok) {
                    val saldoText = if (saldoRupiah > 0) {
                        "Saldo kamu saat ini: Rp$saldoRupiah."
                    } else {
                        "Saldo kamu kosong. Isi saldo dulu supaya permintaan bisa diproses."
                    }
                    AlertDialog.Builder(this@ProfileActivity)
                        .setTitle("Model di luar paket")
                        .setMessage(
                            "\"${m.name}\" tidak termasuk paket ${plan ?: "kamu"}. " +
                                "Pemakaian akan dikenakan saldo wallet (tarif PAYG, lebih mahal). " +
                                saldoText
                        )
                        .setPositiveButton("Lanjutkan") { _, _ ->
                            prefs.edit()
                                .putString(NativeChatActivity.KEY_MODEL, m.id)
                                .putBoolean(NativeChatActivity.KEY_MODEL_SET, true)
                                .apply()
                            txtCurrentModel.text = getString(R.string.model_none_selected, m.name)
                            Toast.makeText(
                                this@ProfileActivity,
                                getString(R.string.model_switched, m.name),
                                Toast.LENGTH_SHORT
                            ).show()
                            dlg.dismiss()
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                    return@setOnClickListener
                }
                prefs.edit()
                    .putString(NativeChatActivity.KEY_MODEL, m.id)
                    .putBoolean(NativeChatActivity.KEY_MODEL_SET, true)
                    .apply()
                txtCurrentModel.text = getString(R.string.model_none_selected, m.name)
                Toast.makeText(
                    this@ProfileActivity,
                    getString(R.string.model_switched, m.name),
                    Toast.LENGTH_SHORT
                ).show()
                dlg.dismiss()
            }
            list.addView(row)
        }

        fun renderList() {
            list.removeAllViews()
            val inside = models.filter { isAllowed(it) }
            val outside = models.filter { !isAllowed(it) }
            inside.forEach { addRow(it, true) }
            if (outside.isNotEmpty()) {
                val header = TextView(this).apply {
                    text = "Model lain — bayar saldo / butuh paket (${outside.size})  ${if (showOutside) "▴" else "▾"}"
                    setTextColor(getColor(R.color.textSecondary))
                    textSize = 12.5f
                    setPadding(dp(12), dp(14), dp(12), dp(10))
                    setOnClickListener {
                        showOutside = !showOutside
                        renderList()
                    }
                }
                list.addView(header)
                if (showOutside) outside.forEach { addRow(it, false) }
            }
        }

        if (!plan.isNullOrEmpty() && !allowed.isNullOrEmpty()) {
            val seeOther = TextView(this).apply {
                text = "Lihat paket lain ›"
                setTextColor(getColor(R.color.primary))
                textSize = 13f
                setPadding(dp(12), dp(6), dp(12), dp(12))
                setOnClickListener {
                    try {
                        startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.sahabatmobile.com/ai/packages")
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            }
            box.addView(seeOther)
        }
        box.addView(list)

        renderList()
        scroll.addView(box)

        dlg = AlertDialog.Builder(this)
            .setTitle(R.string.choose_model)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.show()
    }

    /** Daftar model yang ditanggung paket (disimpan dari deteksi akses di layar chat). */
    private fun readAllowedModels(): List<String>? {
        val raw = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
            .getString(NativeChatActivity.KEY_ALLOWED, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) list.add(arr.optString(i))
            list
        } catch (_: Exception) {
            null
        }
    }

    /** Fitur #4: dialog tambah persona kontak — nama + pilih gaya. */
    private fun showAddContactPersonaDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Nama kontak WhatsApp (contoh: Pak Iwan)"
            setPadding(48, 32, 48, 16)
        }
        val modeLabels = arrayOf("Sales / Jualan", "Personal / Teman", "Formal / Sopan", "Campuran (AI deteksi)")
        val modeKeys = arrayOf("sales", "personal", "formal", "campuran")
        val spinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@ProfileActivity,
                android.R.layout.simple_spinner_dropdown_item,
                modeLabels
            )
            setPadding(48, 16, 48, 16)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(input)
            addView(spinner)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("➕ Kontak Khusus")
            .setMessage("Nama harus mirip dengan nama kontak di WhatsApp.")
            .setView(container)
            .setPositiveButton("Simpan") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Nama kontak masih kosong", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                WhatsAppAiClient.addContactPersona(this, name, modeKeys[spinner.selectedItemPosition])
                Toast.makeText(this, getString(R.string.wa_contact_saved), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Fitur #4: kelola persona kontak — tap item untuk hapus. */
    private fun showManageContactPersonaDialog() {
        val arr = WhatsAppAiClient.loadContactPersonas(this)
        if (arr.length() == 0) {
            Toast.makeText(this, getString(R.string.wa_contact_none), Toast.LENGTH_SHORT).show()
            return
        }
        val modeLabels = mapOf(
            "sales" to "Sales / Jualan",
            "personal" to "Personal / Teman",
            "formal" to "Formal / Sopan",
            "campuran" to "Campuran (AI deteksi)"
        )
        val items = arrayOfNulls<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            items[i] = "👤 ${o.optString("name")} — ${modeLabels[o.optString("mode")] ?: o.optString("mode")}"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📋 Kontak Khusus")
            .setItems(items) { _, which ->
                WhatsAppAiClient.removeContactPersona(this, which)
                Toast.makeText(this, getString(R.string.wa_sched_deleted), Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Tutup", null)
            .show()
    }

    /** Fitur #3: dialog buat pesan terjadwal WA — teks → tanggal → jam. */
    private fun showAddScheduledDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Ketik pesan WhatsApp di sini..."
            minLines = 3
            gravity = android.view.Gravity.TOP
            setPadding(48, 32, 48, 16)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("➕ Pesan Terjadwal WA")
            .setMessage("Tulis pesannya, lalu pilih waktu kirim.")
            .setView(input)
            .setPositiveButton("Pilih Waktu ⏰") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, "Pesan masih kosong", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pickScheduledDateTime(text)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun pickScheduledDateTime(text: String) {
        val cal = java.util.Calendar.getInstance()
        android.app.DatePickerDialog(
            this,
            { _, year, month, day ->
                cal.set(year, month, day)
                android.app.TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
                        cal.set(java.util.Calendar.MINUTE, minute)
                        cal.set(java.util.Calendar.SECOND, 0)
                        if (cal.timeInMillis <= System.currentTimeMillis()) {
                            Toast.makeText(this, "Waktu harus di masa depan!", Toast.LENGTH_SHORT).show()
                        } else {
                            ScheduledMessageReceiver.add(this, text, cal.timeInMillis)
                            Toast.makeText(this, getString(R.string.wa_sched_saved), Toast.LENGTH_SHORT).show()
                        }
                    },
                    cal.get(java.util.Calendar.HOUR_OF_DAY),
                    cal.get(java.util.Calendar.MINUTE),
                    true
                ).show()
            },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** Fitur #3: kelola pesan terjadwal — tap item untuk hapus. */
    private fun showManageScheduledDialog() {
        val arr = ScheduledMessageReceiver.loadAll(this)
        if (arr.length() == 0) {
            Toast.makeText(this, getString(R.string.wa_sched_none), Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale("id"))
        val items = arrayOfNulls<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val label = o.optString("text").take(60).replace("\n", " ")
            items[i] = "⏰ ${fmt.format(java.util.Date(o.optLong("at")))}\n${label}"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📋 Pesan Terjadwal")
            .setItems(items) { _, which ->
                val item = arr.getJSONObject(which)
                ScheduledMessageReceiver.cancelAlarm(this, item)
                ScheduledMessageReceiver.remove(this, item.optString("id"))
                Toast.makeText(this, getString(R.string.wa_sched_deleted), Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun updateSummaryTimeLabel(p: android.content.SharedPreferences) {
        val hour = p.getInt("daily_summary_hour", 7)
        val minute = p.getInt("daily_summary_minute", 0)
        val enabled = p.getBoolean("daily_summary_enabled", true)
        val status = if (enabled) "Aktif" else "Nonaktif"
        btnPickSummaryTime.text = String.format("⏰ %02d:%02d — %s", hour, minute, status)
    }

    /** Fitur #9: label status mode fokus + auto-expire. */
    private fun updateFocusStatus() {
        val p = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
        val until = p.getLong("focus_until", 0L)
        val on = p.getBoolean("focus_mode", false) && until > System.currentTimeMillis()
        tvFocusStatus.text = if (on) {
            val t = java.text.SimpleDateFormat("HH:mm").format(java.util.Date(until))
            "🌙 Aktif — balasan otomatis pakai pesan fokus sampai $t"
        } else {
            if (p.getBoolean("focus_mode", false)) {
                p.edit().putBoolean("focus_mode", false).apply() // kedaluwarsa
            }
            "Nonaktif"
        }
    }

    /** Fitur #10: tulis semua data prefs ke file JSON (SAF). */
    private fun writeBackup(uri: android.net.Uri) {
        try {
            val p = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
            val obj = org.json.JSONObject()
            for ((key, value) in p.all) {
                val e = org.json.JSONObject()
                when (value) {
                    is String -> { e.put("t", "s"); e.put("v", value) }
                    is Int -> { e.put("t", "i"); e.put("v", value) }
                    is Long -> { e.put("t", "l"); e.put("v", value) }
                    is Boolean -> { e.put("t", "b"); e.put("v", value) }
                    is Float -> { e.put("t", "f"); e.put("v", value.toDouble()) }
                    is Set<*> -> {
                        e.put("t", "set")
                        e.put("v", org.json.JSONArray(value))
                    }
                }
                obj.put(key, e)
            }
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(obj.toString(2).toByteArray())
            }
            Toast.makeText(this, "Backup tersimpan! 💾", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
                    Toast.makeText(this, "Backup gagal. Coba lagi sebentar.", Toast.LENGTH_LONG).show()
        }
    }

    /** Fitur #10: pulihkan data dari file JSON backup. */
    private fun readBackup(uri: android.net.Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            val obj = org.json.JSONObject(text)
            val p = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
            val ed = p.edit()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val e = obj.optJSONObject(key) ?: continue
                when (e.optString("t")) {
                    "s" -> ed.putString(key, e.optString("v"))
                    "i" -> ed.putInt(key, e.optInt("v"))
                    "l" -> ed.putLong(key, e.optLong("v"))
                    "b" -> ed.putBoolean(key, e.optBoolean("v"))
                    "f" -> ed.putFloat(key, e.optDouble("v").toFloat())
                    "set" -> {
                        val arr = e.optJSONArray("v")
                        val set = HashSet<String>()
                        for (i in 0 until arr.length()) set.add(arr.optString(i))
                        ed.putStringSet(key, set)
                    }
                }
            }
            ed.apply()
            Toast.makeText(this, "Data dipulihkan! 📥", Toast.LENGTH_SHORT).show()
            recreate()
        } catch (e: Exception) {
                    Toast.makeText(this, "Restore gagal. Coba lagi sebentar.", Toast.LENGTH_LONG).show()
        }
    }

    /** Update otomatis: cek rilis terbaru aplikasi di GitHub. */
    private fun checkUpdate() {
        val current = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        btnUpdate.isEnabled = false
        btnUpdate.text = getString(R.string.updater_checking)
        Thread {
            val rel = Updater.latest()
            runOnUiThread {
                btnUpdate.isEnabled = true
                btnUpdate.text = getString(R.string.updater_check)
                if (rel == null) {
                    Toast.makeText(this, R.string.updater_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (!Updater.isNewer(rel.tag, current)) {
                    Toast.makeText(
                        this, getString(R.string.updater_uptodate, current), Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.updater_new_title, rel.tag))
                    .setMessage(rel.notes.ifBlank { getString(R.string.updater_new_desc) })
                    .setPositiveButton(R.string.updater_download) { _, _ -> downloadAndInstall(rel) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }.start()
    }

    private fun downloadAndInstall(rel: Updater.Release) {
        if (!Updater.ensureInstallAllowed(this)) {
            Toast.makeText(this, R.string.updater_allow_install, Toast.LENGTH_LONG).show()
        }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(12), dp(20), 0)
        val pb = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pb.isIndeterminate = false
        pb.max = 100
        box.addView(pb)
        val tv = TextView(this)
        tv.text = getString(R.string.updater_progress, 0)
        tv.textSize = 12f
        tv.setPadding(0, dp(8), 0, dp(12))
        box.addView(tv)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.updater_download)
            .setView(box)
            .setCancelable(false)
            .create()
        dialog.show()
        Updater.download(this, rel.apkUrl, rel.apkName) { pct, file ->
            runOnUiThread {
                if (pct < 0) {
                    dialog.dismiss()
                    Toast.makeText(this, R.string.updater_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (file != null) {
                    dialog.dismiss()
                    Toast.makeText(this, R.string.updater_done, Toast.LENGTH_SHORT).show()
                    Updater.install(this, file)
                } else {
                    pb.progress = pct
                    tv.text = getString(R.string.updater_progress, pct)
                }
            }
        }
    }

    private fun refreshFinanceSummary() {
        val list = AssistantData.loadFin(this)
        val now = java.util.Calendar.getInstance()
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.YEAR, now.get(java.util.Calendar.YEAR), java.util.Calendar.MONTH, 1, 0, 0)
        val monthStart = cal.timeInMillis
        val monthEnd = cal.timeInMillis + 31L * 24 * 60 * 60 * 1000L
        var expense = 0.0
        var income = 0.0
        for (f in list) {
            if (f.ts in monthStart until monthEnd) {
                if (f.type == "expense") expense += f.amount else income += f.amount
            }
        }
        val fmt = String.format(java.util.Locale("id"), "%,.0f", expense)
        val fmtIn = String.format(java.util.Locale("id"), "%,.0f", income)
        tvFinTotals.text = if (list.isEmpty()) getString(R.string.fin_totals_none) else
            String.format(
                java.util.Locale("id"),
                "Pengeluaran bulan ini: Rp %s\nPemasukan bulan ini: Rp %s",
                fmt, fmtIn
            )
    }

    private fun clearHistory() {
        getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE).edit()
            .putString(NativeChatActivity.KEY_SESSIONS, "[]").apply()
        Toast.makeText(this, "Riwayat chat dihapus", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
