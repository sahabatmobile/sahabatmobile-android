# SahabatMobile Chat — Android

Aplikasi AI Android untuk pelanggan SahabatMobile. Semua fitur bekerja dengan satu API key (`/api/v1`) dan model aktif (default `google/gemini-2.5-flash`, bisa LongCat 2.5 dll).

## 📱 Daftar Fitur

### 1. Chat Web (WebView)
- Memuat `https://www.sahabatmobile.com/ai/prompt` di WebView — sesi login & chat tetap tersimpan (cookie + DOM storage flush ke disk).
- Swipe-to-refresh, progress bar, upload file, retry saat offline, back navigation.
- Link di luar domain sahabatmobile.com dibuka di browser eksternal.

### 2. Chat API (Native)
- Chat langsung via API Gateway: `POST /api/v1/chat/completions` (streaming SSE) + `GET /api/v1/models` (daftar model live).
- Riwayat percakapan tersimpan per sesi (bisa dibuka lewat drawer "Riwayat Chat").
- **Model sesuai paket** — dari `GET /api/v1/me/status`; model di luar paket dinonaktifkan (kecuali "Tampilkan semua").
- **Web search tool** (`web_search`) — AI mencari info/berita terbaru sebelum menjawab.

### 3. AI Voice Call 📞 *(baru)*
- Panggilan suara langsung dengan AI: rekam mic → ASR (`mimo-v2.5-asr`) → balasan AI → TTS → dengarkan lagi (siklus otomatis).
- **TTS LongCat** (suara natural + voice clone via `/v1/audio/speech`), fallback TTS Android on-device.
- Interupsi: tekan mic saat AI bicara untuk memotong; timer panggilan; pengaturan suara (API key LongCat + voice ID).
- Persona voice call: AI tahu sedang **menelpon** (bukan chat teks).

### 4. Kamera Solusi 📷 *(baru)*
- Foto masalah apa pun (error layar, barang rusak, mesin, dokumen, soal) → AI memberi:
  **Diagnosis → Langkah Solusi → Pencegahan → Perlu Ahli?**
- Dirancang untuk kondisi "tidak ada sumber akurat untuk ditanyai" — AI tidak boleh mengarang spesifikasi yang tidak terlihat di foto.
- Foto dari kamera atau galeri; hasil bisa disalin.

### 5. Template Kerja 📝 *(baru)*
- 10 jenis dokumen siap pakai: Email Profesional, Surat Lamaran, Invoice, Laporan, Proposal, Notulen, Penawaran Harga, Surat Permohonan, Materi Belajar, dll.
- Cukup isi brief singkat → dokumen lengkap dengan **placeholder `[seperti ini]`** untuk bagian yang perlu diedit.
- Output markdown rapi; hasil bisa disalin.

### 6. WhatsApp AI Reply 💬
- **Manual**: `WhatsAppReplyActivity` — ketik pesan masuk → AI → saran balasan → kirim ke WhatsApp.
- **Otomatis**: `WhatsAppNotificationListener` — notifikasi WA masuk → AI → auto-kirim balasan.
- Deteksi kata kunci sales, persona gaya (sales/teman/formal/campuran), **persona per kontak**, **auto-terjemahan** (balas sesuai bahasa pelanggan), FAQ & info produk.

### 7. Pesan Terjadwal WhatsApp ⏰
- Jadwalkan pesan WA (teks → tanggal → jam) yang otomatis terkirim lewat `ScheduledMessageReceiver`.

### 8. Suara AI (TTS) 🔊
- Balasan chat bisa dibacakan dengan suara (bahasa Indonesia) — toggle di Pengaturan.
- Long-press bubble balasan untuk mendengarkan ulang.

### 9. Input Suara 🎤
- Rekam PCM 16 kHz → WAV → ASR server (`mimo-v2.5-asr`) → otomatis dikirim sebagai pesan.
- Fallback dikte on-device (SpeechRecognizer) bila ASR gagal.

### 10. Kirim Gambar & Pemindaian 🖼️
- Gambar diperkecil (≤1280px, JPEG) → dikirim multimodal `image_url` (data-URI) dengan preview + hapus.
- **4 mode pemindaian**: 📷 Kalori (foto makanan), 🧾 Nota/Struk, 🛍️ Deskripsi Produk, 📄 Ekstrak Teks (OCR).

### 11. Susun Hari Saya 📅
- AI planner: kirim konteks kegiatan → AI menyusun jadwal → parsing otomatis jadi tugas.

### 12. Dashboard Dinamis 📊
- Ringkasan harian: tugas tertunda, aktivitas, gaya partner AI (persona chips: Umum, Teman Curhat, Dokter Umum, dll).

### 13. Widget Home Screen 📱
- Widget Android berisi ringkasan/tugas — refresh otomatis (`AssistantWidgetProvider`).

### 14. Catatan Keuangan 💰
- Lacak pengeluaran/pemasukan bulanan (`AssistantData.loadFin`) — ringkasan di Profil.

### 15. Mode Fokus 🧘
- Aktifkan mode fokus → auto-reply WA tanpa AI (pesan fokus), auto-expire.

### 16. Backup & Export 💾
- Ekspor semua data (pref + riwayat) ke file JSON (SAF) & pulihkan kembali — menu Profil.

### 17. Papan Hasil AI 📌
- Tombol **"Pin ke Papan"** di setiap kartu saran produk Shopee di chat → produk disimpan ke papan tersendiri.
- Anti-duplikat (URL sama tidak masuk dua kali, query diabaikan), kartu terbaru di atas, maksimal 60 kartu.
- Tampilkan gambar, judul, rating toko (★), dan kata kunci pencarian; bisa dibuka langsung di app Shopee, dihapus satu per satu, atau dibersihkan semua.
- Dibuka dari drawer **"Papan Hasil AI"** (dengan jumlah kartu) atau menu ⋮ → Papan Hasil AI.

## 🛠 Cara Build

**Kebutuhan:** JDK 17 · Android SDK 36 · Gradle 8.14 (sudah termasuk wrapper)

### Android Studio (disarankan)
1. Buka folder project ini di **Android Studio** (Ladybug atau lebih baru).
2. Sinkron Gradle otomatis (butuh internet untuk download dependency).
3. **Run** ke HP/emulator (min Android 8.0 / API 26), atau `Build > Build APK(s)`.

### Terminal (tanpa Android Studio)
```bash
# Windows
gradlew assembleDebug

# Linux / macOS
./gradlew assembleDebug
```
APK debug: `app/build/outputs/apk/debug/app-debug.apk`

> Catatan: pastikan `sdk.dir` di `local.properties` menunjuk ke Android SDK (otomatis dibuat Android Studio).

## 🚀 Rilis (Play Store)

- Ganti `applicationId` di `app/build.gradle` bila perlu.
- Isi `versionCode`/`versionName`.
- Buat keystore signing (Build > Generate Signed Bundle/APK) untuk upload ke Play Console.

## 📂 Struktur

```
├── gradlew / gradlew.bat        # Gradle wrapper (build tanpa install Gradle)
├── gradle/wrapper/              # gradle-wrapper.jar + properties (Gradle 8.14)
├── build.gradle / settings.gradle
└── app/src/main/
    ├── java/com/sahabatmobile/chat/
    │   ├── MainActivity.kt               # Chat Web (WebView)
    │   ├── NativeChatActivity.kt         # Chat API native + drawer + menu utama
    │   ├── VoiceCallActivity.kt          # AI Voice Call 📞
    │   ├── VoiceAiClient.kt              # ASR + TTS LongCat untuk voice call
    │   ├── SolutionCameraActivity.kt     # Kamera Solusi 📷
    │   ├── TemplateActivity.kt           # Template Kerja 📝
    │   ├── PapanActivity.kt              # Papan Hasil AI 📌
    │   ├── PapanStore.kt                 # Simpan kartu papan (pin/anti-duplikat)
    │   ├── ShopeeSearch.kt               # Cari produk Shopee (Bing/DuckDuckGo)
    │   ├── AiTools.kt                    # Helper AI multimodal (gambar + teks)
    │   ├── WhatsAppReplyActivity.kt      # Saran balasan WA (manual)
    │   ├── WhatsAppAiClient.kt           # AI client untuk WA (persona, auto-translate)
    │   ├── WhatsAppNotificationListener.kt # Auto-reply WA (notifikasi)
    │   ├── ScheduledMessageReceiver.kt   # Pesan terjadwal WA
    │   ├── AssistantActivity.kt          # Asisten (tugas & pengingat)
    │   ├── AssistantData.kt              # Data tugas, keuangan, widget
    │   ├── AssistantWidgetProvider.kt    # Widget home screen
    │   ├── DailySummaryReceiver.kt       # Ringkasan harian
    │   ├── ProfileActivity.kt            # Profil & Pengaturan + Backup
    │   ├── PromptActivity.kt             # Pilih prompt AI
    │   ├── SettingsApi.kt                # Helper API untuk Pengaturan
    │   └── Markdown.kt                   # Render markdown (bubble AI)
    ├── res/ (layout, menu, drawable, theme, icon, strings)
    ├── assets/ (assistants.json, prompts.json)
    └── AndroidManifest.xml
```

## 🔑 API

Semua fitur memakai API key yang sama — dapatkan dari `/ai/api-keys` atau `/ai/plan-details` di website. API key disimpan di SharedPreferences perangkat, tidak dikirim ke mana pun.

- `GET /api/v1/models` — daftar model
- `POST /api/v1/chat/completions` — chat (streaming, multimodal, tools)
- `GET /api/v1/me/status` — status paket (membatasi model)
- `POST https://api.longcat.chat/v1/audio/speech` — TTS LongCat (opsional, untuk AI Voice Call)
