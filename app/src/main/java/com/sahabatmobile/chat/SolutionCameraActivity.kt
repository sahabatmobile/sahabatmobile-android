package com.sahabatmobile.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fitur #2: Kamera Solusi — foto masalah (error, barang rusak, mesin, dokumen,
 * soal, apa pun) → AI memberi SOLUSI LANGKAH-DEMI-LANGKAH yang mudah dipraktikkan.
 * Dirancang untuk kondisi "tidak ada sumber akurat yang bisa ditanyai".
 */
class SolutionCameraActivity : AppCompatActivity() {

    private var imagePath: String? = null
    private var cameraUri: Uri? = null

    private lateinit var preview: ImageView
    private lateinit var question: EditText
    private lateinit var result: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnSolve: Button

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) processImageUri(uri) }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) cameraUri?.let { processImageUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_solution_camera)

        preview = findViewById(R.id.solutionPreview)
        question = findViewById(R.id.solutionQuestion)
        result = findViewById(R.id.solutionResult)
        progress = findViewById(R.id.solutionProgress)
        btnSolve = findViewById(R.id.btnSolutionSolve)

        findViewById<Button>(R.id.btnSolutionCamera).setOnClickListener { openCamera() }
        findViewById<Button>(R.id.btnSolutionGallery).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        findViewById<Button>(R.id.btnSolutionClear).setOnClickListener { clearAll() }
        btnSolve.setOnClickListener { solve() }
        findViewById<Button>(R.id.btnSolutionCopy).setOnClickListener {
            val text = result.text.toString()
            if (text.isBlank() || text == getString(R.string.solution_placeholder)) {
                Toast.makeText(this, R.string.solution_nothing, Toast.LENGTH_SHORT).show()
            } else {
                copyToClipboard(text)
            }
        }
    }

    private fun openCamera() {
        try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
            cameraUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            takePictureLauncher.launch(cameraUri)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.solution_camera_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImageUri(uri: Uri) {
        try {
            val bitmap = AiTools.decodeScaled(this, uri, 1280) ?: run {
                Toast.makeText(this, R.string.solution_image_failed, Toast.LENGTH_SHORT).show()
                return
            }
            imagePath = AiTools.saveImage(this, bitmap)
            preview.setImageBitmap(bitmap)
            preview.clipToOutline = true
            preview.visibility = View.VISIBLE
            Toast.makeText(this, R.string.solution_image_ok, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.solution_image_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearAll() {
        imagePath = null
        preview.setImageDrawable(null)
        preview.visibility = View.GONE
        question.setText("")
        result.text = getString(R.string.solution_placeholder)
    }

    private fun solve() {
        if (imagePath == null) {
            Toast.makeText(this, R.string.solution_need_image, Toast.LENGTH_SHORT).show()
            return
        }
        val apiKey = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
            .getString(NativeChatActivity.KEY_API, null)
        if (apiKey.isNullOrEmpty()) {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_LONG).show()
            return
        }
        val q = question.text.toString().trim()
        btnSolve.isEnabled = false
        progress.visibility = View.VISIBLE
        result.text = getString(R.string.solution_thinking)

        val system = """
## KAMU ADALAH AHLI SOLUSI MASALAH 🔧
Pengguna mengirim foto masalahnya (bisa error layar, barang rusak, mesin, dokumen,
soal, dan sejenisnya) karena TIDAK ADA sumber akurat lain untuk ditanyai.
Tugasmu: berikan solusi yang LANGSUNG BISA DIPRAKTIKKAN.

FORMAT WAJIB (markdown):
**🔍 Diagnosis** — identifikasi masalah dari foto (ringkas)
**🛠️ Langkah Solusi** — bernomor 1, 2, 3... jelas dan spesifik
**🛡️ Pencegahan** — tips agar masalah tidak terulang
**⚠️ Perlu Ahli?** — sebutkan jika sebaiknya dibawa ke profesional (atau "tidak perlu")

ATURAN:
- JANGAN MENGARANG spesifikasi yang tidak terlihat di foto — jika ragu, tulis asumsimu.
- Bahasa Indonesia yang jelas, singkat, tanpa bertele-tele.
- Jika foto BUKAN masalah (misal orang/benda biasa), jelaskan apa yang terlihat lalu tanya masalahnya apa.
"""
        val user = if (q.isEmpty()) "Tolong analisis foto ini dan berikan solusinya."
        else "Masalah saya: $q\n\nTolong analisis fotonya dan berikan solusi."

        CoroutineScope(Dispatchers.IO).launch {
            val answer = AiTools.ask(
                this@SolutionCameraActivity,
                system,
                user,
                imagePath,
                maxTokens = 1600
            )
            withContext(Dispatchers.Main) {
                btnSolve.isEnabled = true
                progress.visibility = View.GONE
                result.text = answer ?: getString(R.string.solution_error)
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("solusi", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
