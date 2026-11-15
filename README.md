# SahabatMobile Chat — Android

Aplikasi chat Android untuk pelanggan SahabatMobile. Dua mode:

1. **Chat Web (utama)** — mode chat memuat `https://www.sahabatmobile.com/ai/prompt` di WebView.
   - Sesi login & chat pelanggan tetap tersimpan (cookie + DOM storage di-flush ke disk).
   - Support swipe-to-refresh, progress bar, upload file, retry saat offline, back navigation.
   - Link di luar domain sahabatmobile.com dibuka di browser eksternal.
   - Menu **Hapus Sesi** untuk logout / login ulang.

2. **Chat API (native)** — chat langsung via API Gateway sesuai [docs](https://www.sahabatmobile.com/docs/api-reference):
   - `GET /api/v1/models` — daftar model dimuat live dari server (tidak di-hardcode).
   - `POST /api/v1/chat/completions` dengan `stream: true` — jawaban muncul kata-per-kata (SSE).
   - `GET /api/v1/me/status` — status paket dipakai untuk **membatasi model sesuai paket**.
   - Auth `Authorization: Bearer sk-...` (key dari `/ai/api-keys` atau `/ai/plan-details`).
   - API key & model tersimpan di SharedPreferences perangkat, tidak dikirim ke mana pun.
   - Riwayat percakapan (messages[]) dipertahankan selama sesi.

3. **Kirim gambar** — tombol gambar di composer (galeri atau kamera).
   - Gambar diperkecil (≤1280px, JPEG) lalu dikirim sebagai konten multimodal `image_url` (data-URI).
   - Preview + tombol hapus sebelum dikirim; bubble user menampilkan gambarnya.

4. **Kirim suara** — tombol mikrofon merekam PCM 16 kHz mono (bar rekam + timer + level meter).
   - Audio dibungkus WAV lalu ditranskripsi via model ASR gateway (`mimo-v2.5-asr`), hasilnya otomatis dikirim sebagai pesan.
   - Bila ASR server gagal, otomatis fallback ke dikte on-device (SpeechRecognizer).

5. **Model sesuai paket** — daftar model selalu mengikuti server (`/api/v1/models`), tanpa hardcode.
   - Paket dideteksi dari `GET /api/v1/me/status` (`ada_paket` + `nama_paket`), dicocokkan dengan peta
     `model_config` paket yang sama dipakai aplikasi desktop.
   - Untuk pengguna paket: model di luar paket **dinonaktifkan** (abu-abu, "di luar paket") agar saldo
     tidak terpotong; bisa diaktifkan lewat centang "Tampilkan semua".
   - Untuk pengguna PAYG (tanpa paket): semua model boleh dipakai.
   - Model aktif otomatis dialihkan ke model paket bila model sebelumnya tidak sah.

## Cara Build

Buka folder `android/` di **Android Studio** (Ladybug atau lebih baru):

1. Android Studio akan otomatis sinkron Gradle (butuh internet untuk download Gradle 8.7 & SDK 34).
2. Jika diminta "Install missing SDK components", klik **Install**.
3. Build → `Build > Build Bundle(s)/APK(s) > Build APK(s)`, atau tekan **Run** dengan HP/emulator terhubung (min Android 8.0 / API 26).

Build via terminal (alternatif):

```
cd android
gradle wrapper        # sekali, jika gradlew belum ada
./gradlew assembleDebug
```

APK debug: `android/app/build/outputs/apk/debug/app-debug.apk`.

## Rilis (Play Store)

- Ganti `applicationId` di `app/build.gradle` bila perlu.
- Isi `versionCode`/`versionName`.
- Buat keystore signing (Build > Generate Signed Bundle/APK) untuk upload ke Play Console.

## Struktur

```
android/
├── app/src/main/
│   ├── java/com/sahabatmobile/chat/
│   │   ├── MainActivity.kt        # Chat Web: WebView /ai/prompt + sesi persisten
│   │   └── NativeChatActivity.kt  # Chat API: /chat/completions + /models (streaming)
│   ├── res/ (layout, menu, theme, icon, strings)
│   └── AndroidManifest.xml
├── build.gradle / settings.gradle / gradle.properties
└── gradle/wrapper/gradle-wrapper.properties
```
