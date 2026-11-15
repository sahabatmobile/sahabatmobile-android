package com.sahabatmobile.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fitur #4: Template Kerja Otomatis — generate surat, email, invoice, laporan,
 * proposal, dan dokumen kerja lainnya dari brief singkat. Dirancang untuk
 * mempercepat pekerjaan saat tidak ada template/contoh yang bisa dijadikan rujukan.
 */
class TemplateActivity : AppCompatActivity() {

    private data class TemplateType(val label: String, val instruction: String)

    private val templateTypes = listOf(
        TemplateType(
            "📧 Email Profesional",
            "Buat email profesional yang sopan, jelas, dan to the point. Sertakan subjek email, salam pembuka, isi, dan salam penutup."
        ),
        TemplateType(
            "📄 Surat Lamaran Kerja",
            "Buat surat lamaran kerja formal bahasa Indonesia yang profesional: kop/nama pelamar, tujuan, paragraf pembuka yang menarik, ringkasan keahlian, penutup, dan tanda tangan."
        ),
        TemplateType(
            "🧾 Invoice / Tagihan",
            "Buat invoice profesional: nomor invoice, tanggal, info pihak (penjual & pembeli), daftar item (deskripsi, qty, harga), subtotal, pajak bila ada, total, metode pembayaran, dan catatan."
        ),
        TemplateType(
            "📊 Laporan Kerja",
            "Buat laporan kerja terstruktur: judul, periode, ringkasan eksekutif, tujuan, hasil/temuan, analisis, rekomendasi, dan kesimpulan."
        ),
        TemplateType(
            "🤝 Proposal Bisnis",
            "Buat proposal bisnis: latar belakang, tujuan, lingkup kerja, metodologi/timeline, anggaran, manfaat, dan penutup dengan call-to-action."
        ),
        TemplateType(
            "📋 Notulen Rapat",
            "Buat notulen rapat: info rapat (tanggal, waktu, peserta), agenda, diskusi per poin, keputusan yang diambil, action items (penanggung jawab + tenggat), dan rapat lanjutan bila ada."
        ),
        TemplateType(
            "💰 Penawaran Harga",
            "Buat surat penawaran harga yang menarik: pembuka, identitas produk/jasa, detail penawaran + harga, keunggulan, masa berlaku penawaran, dan call-to-action."
        ),
        TemplateType(
            "📝 Surat Permohonan / Izin",
            "Buat surat permohonan/izin formal: kop, tujuan surat, identitas pemohon, isi permohonan + alasan, tanggal, dan tanda tangan."
        ),
        TemplateType(
            "🎓 Materi Pembelajaran",
            "Buat materi pembelajaran yang mudah dipahami: judul, tujuan belajar, penjelasan konsep bertahap, contoh konkret, latihan soal, dan rangkuman."
        ),
        TemplateType(
            "✨ Lainnya (deskripsi bebas)",
            "Buat dokumen sesuai permintaan pengguna dengan format yang rapi dan profesional."
        )
    )

    private lateinit var spinner: Spinner
    private lateinit var brief: EditText
    private lateinit var result: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnGenerate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_template)

        spinner = findViewById(R.id.templateType)
        brief = findViewById(R.id.templateBrief)
        result = findViewById(R.id.templateResult)
        progress = findViewById(R.id.templateProgress)
        btnGenerate = findViewById(R.id.btnTemplateGenerate)

        val labels = templateTypes.map { it.label }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                brief.hint = hintFor(pos)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnGenerate.setOnClickListener { generate() }
        findViewById<Button>(R.id.btnTemplateCopy).setOnClickListener {
            val text = result.text.toString()
            if (text.isBlank() || text == getString(R.string.template_placeholder)) {
                Toast.makeText(this, R.string.template_nothing, Toast.LENGTH_SHORT).show()
            } else {
                copyToClipboard(text)
            }
        }
        findViewById<Button>(R.id.btnTemplateClear).setOnClickListener {
            brief.setText("")
            result.text = getString(R.string.template_placeholder)
        }
    }

    private fun hintFor(pos: Int): String = when (pos) {
        0 -> "Contoh: Email ke klien soal revisi deadline proyek website jadi 20 Desember"
        1 -> "Contoh: Lamaran kerja posisi Staff Marketing di PT Maju Jaya, saya S1 Manajemen, pengalaman 2 tahun"
        2 -> "Contoh: Invoice jasa desain logo untuk Toko Bunga Indah, 3 item, total 1,5 juta"
        3 -> "Contoh: Laporan penjualan bulan Oktober, target tercapai 110%, produk terlaris A"
        4 -> "Contoh: Proposal kerja sama pemasaran online untuk UMKM kuliner, budget 5 juta/bulan"
        5 -> "Contoh: Notulen rapat tim marketing 15 Nov, bahas campaign akhir tahun"
        6 -> "Contoh: Penawaran paket catering pernikahan untuk 300 tamu"
        7 -> "Contoh: Surat izin tidak masuk kerja karena sakit, tanggal 16-17 Nov"
        8 -> "Contoh: Materi belajar matematika kelas 5 tentang pecahan"
        else -> "Tulis jenis dokumen dan detail yang dibutuhkan"
    }

    private fun generate() {
        val b = brief.text.toString().trim()
        if (b.isEmpty()) {
            Toast.makeText(this, R.string.template_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val apiKey = getSharedPreferences(NativeChatActivity.PREFS, MODE_PRIVATE)
            .getString(NativeChatActivity.KEY_API, null)
        if (apiKey.isNullOrEmpty()) {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_LONG).show()
            return
        }
        val type = templateTypes[spinner.selectedItemPosition]

        btnGenerate.isEnabled = false
        progress.visibility = View.VISIBLE
        result.text = getString(R.string.template_thinking)

        val system = """
## KAMU ADALAH ASISTEN PENULIS DOKUMEN PROFESIONAL 📝
Tugasmu: buatkan dokumen kerja yang SIAP PAKAI dari brief pengguna.

JENIS DOKUMEN: ${type.label}
INSTRUKSI KHUSUS: ${type.instruction}

ATURAN WAJIB:
1. Gunakan format markdown yang rapi (judul, subjudul, poin bernomor bila perlu).
2. Isi detail yang tidak disebutkan pengguna dengan placeholder [seperti ini] agar mudah diedit.
3. Gaya bahasa profesional, jelas, dan sesuai konteks Indonesia.
4. Langsung ke isi dokumen — JANGAN menambahkan komentar/pengantar lain di luar dokumen.
5. Buat dokumen yang LENGKAP, bukan contoh singkat.
"""
        val user = "Berikut brief saya:\n$b\n\nTolong buatkan dokumennya sekarang."

        CoroutineScope(Dispatchers.IO).launch {
            val answer = AiTools.ask(
                this@TemplateActivity,
                system,
                user,
                imagePath = null,
                maxTokens = 2200
            )
            withContext(Dispatchers.Main) {
                btnGenerate.isEnabled = true
                progress.visibility = View.GONE
                result.text = answer ?: getString(R.string.template_error)
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("template", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
