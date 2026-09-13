# 🎓 UT Academic Tracker Bot

Bot otomatis berbasis **Java** yang memantau aktivitas e-learning **Universitas Terbuka** secara berkala. Berjalan sepenuhnya di **GitHub Actions** — gratis, tanpa server, tanpa perlu buka laptop.

---

## ✨ Fitur

| Fitur | Keterangan |
|---|---|
| 📋 Deteksi Matkul Baru | Notif Telegram saat semester baru & matkul aktif |
| 📡 Pantau Tugas & Kuis | Ambil dari Moodle Assignment API per-course |
| 💬 Pantau Diskusi Forum | Filter hanya Diskusi, Kehadiran & Tugas yang relevan |
| ✅ Deteksi Sudah Dikerjakan | Baca tanda hijau "Done" langsung dari Activity Completion Moodle |
| 📊 Laporan Periodik | Reminder ke Telegram setiap run (dikendalikan via trigger word) |
| 🔁 Anti-Duplikasi | State management via Notion, aman di environment ephemeral GitHub Actions |
| ⏸️ Trigger Word Stop | Kirim `Stop` ke bot Telegram untuk menghentikan laporan periodik |
| ▶️ Trigger Word Run | Kirim `Run` ke bot Telegram untuk mengaktifkan kembali laporan |
| 🔄 Auto-Resume Matkul Baru | Laporan otomatis aktif kembali jika elearning menambahkan matkul baru |
| 🧹 Reset Akhir Semester | Otomatis berhenti beroperasi & mereset status penanda matkul jika Moodle kosong |

---

## 🏗️ Arsitektur

```
task-tracker/
├── .github/
│   └── workflows/
│       └── jadwal-bot.yml      # Jadwal otomatis (setiap 4 jam)
├── src/main/java/com/autotracker/
│   ├── App.java                # Main + orchestration 4 pengecekan
│   ├── MoodleService.java      # Semua API e-learning Moodle UT
│   ├── NotionService.java      # Semua API Notion (state management)
│   └── TelegramService.java    # Kirim notifikasi & baca keyword Telegram
├── .env.example                # Template variabel lingkungan
├── .gitignore                  # .env diabaikan Git
└── pom.xml                     # Maven dependencies
```

---

## 🔄 Alur Kerja Bot

```
GitHub Actions (tiap 4 jam)
        │
        ▼
   Cek Trigger Word Telegram ("Stop" / "Run")
        │
        ▼
   Login Moodle → Ambil Token & User ID
        │
        ▼
   Ambil Daftar Matkul
        │ (kosong → bot tidur, reset status semester)
        │
        ├─ [CEK 1] Matkul Baru?
        │          └─ Ya → Simpan Notion + Notif Telegram
        │             (+ Auto-resume jika Stop sedang aktif)
        │
        ├─ [CEK 2] Tugas Baru? (Assignment API)
        │          └─ Ya → Simpan Notion + Notif Telegram
        │
        ├─ [CEK 3] Diskusi Forum? (Forum API + Completion API)
        │          ├─ Baru + Belum dikerjakan → Simpan Notion + Notif Telegram
        │          ├─ Sudah ada + Baru selesai → Update Notion → "Selesai" ✅
        │          └─ Sudah dikerjakan tapi baru masuk → Simpan langsung sebagai Selesai
        │
        ├─ [CEK 4] Pesan Dosen? (Messaging API)
        │          └─ Ya (unread > 0) → Notif Telegram
        │
        └─ [LAPORAN] Kirim ringkasan ke Telegram
                   └─ Dilewati jika [STATUS] Stop aktif
```

---

## ⚙️ Setup & Instalasi

### 1. Fork / Clone Repositori

```bash
git clone https://github.com/username-kamu/task-tracker.git
cd task-tracker
```

### 2. Buat Database di Notion

Buat database Notion baru dengan kolom / property berikut:

| Property Name | Tipe |
|---|---|
| `Name` | Title |
| `Mata Kuliah` | Select |
| `Status` | **Status** (bukan Select!) |

> ⚠️ **Penting**: Property `Status` harus bertipe **Status** (bawaan Notion), dan wajib ada opsi bernama **`Selesai`** di grup "Done".

### 3. Buat Notion Integration

1. Buka [notion.so/my-integrations](https://www.notion.so/my-integrations)
2. Klik **"New integration"** → beri nama → Submit
3. Copy **Internal Integration Token** → simpan sebagai `NOTION_TOKEN`
4. Buka database Notion kamu → klik **"..."** → **"Connections"** → tambahkan integration tadi
5. Copy ID database dari URL: `notion.so/{DATABASE_ID}?v=...` → simpan sebagai `NOTION_DATABASE_ID`

### 4. Buat Telegram Bot

1. Chat [@BotFather](https://t.me/BotFather) → `/newbot`
2. Ikuti instruksi → dapatkan **Bot Token** → simpan sebagai `TELEGRAM_BOT_TOKEN`
3. Kirim pesan ke bot kamu, lalu buka `https://api.telegram.org/bot<TOKEN>/getUpdates`
4. Copy `chat.id` dari response → simpan sebagai `TELEGRAM_CHAT_ID`

### 5. Set GitHub Secrets

Di repositori GitHub: **Settings → Secrets and variables → Actions → New repository secret**

| Secret Name | Nilai |
|---|---|
| `UT_NIM` | Nomor Induk Mahasiswa UT |
| `UT_PASS` | Password e-learning UT |
| `TELEGRAM_BOT_TOKEN` | Token bot Telegram |
| `TELEGRAM_CHAT_ID` | Chat ID Telegram kamu |
| `NOTION_TOKEN` | Token integrasi Notion |
| `NOTION_DATABASE_ID` | ID database Notion |

### 6. Jalankan Bot

Bot akan otomatis berjalan setiap **4 jam** sesuai jadwal di `jadwal-bot.yml`.

Untuk menjalankan manual: **Actions → Auto Bot Tracker UT → Run workflow**

---

## 🎮 Trigger Word (Kontrol via Telegram)

Kirim pesan langsung ke bot Telegram kamu untuk mengontrol perilaku laporan:

| Keyword | Efek |
|---|---|
| `Stop` | Menghentikan laporan periodik. Bot tetap cek elearning tapi tidak kirim ringkasan. |
| `Run` | Mengaktifkan kembali laporan periodik. |

> 💡 **Auto-Resume**: Jika kamu mengirim `Stop` lalu elearning menambahkan matkul baru (semester baru), bot **otomatis mengaktifkan kembali** laporan tanpa perlu kirim `Run` manual.

---

## 📱 Contoh Notifikasi Telegram

**Matkul Baru (awal semester):**
```
📚 Semester Baru! 3 matkul aktif:
• Analisis dan Perancangan Sistem
• Kewirausahaan di Era Digital
• Proses Bisnis
```

**Diskusi Baru:**
```
💬 Ada 2 Diskusi BARU!

• Diskusi.1
   📚 Analisis dan Perancangan Sistem
• Diskusi.1
   📚 Perilaku Organisasi
```

**Laporan Periodik (setiap run):**
```
📊 Laporan Bot UT | 2026-04-14
⏰ 09:00:00

⏳ 2 Diskusi belum dikerjakan:

• Diskusi.2
   📚 Kewirausahaan di Era Digital
• Kehadiran Sesi ke-2
   📚 Proses Bisnis

💡 Yuk segera dikerjain sebelum deadline!
```

**Konfirmasi Stop:**
```
⏸️ Bot dihentikan. Laporan periodik tidak akan dikirim sampai kamu kirim 'Run'.

💡 Kirim 'Run' untuk mengaktifkan kembali.
```

**Auto-Resume (saat ada matkul baru):**
```
🆕 Elearning memiliki mata kuliah baru! Laporan periodik diaktifkan kembali secara otomatis.
```

---

## 🗄️ Basis Data Notion

Bot menggunakan Notion sebagai **persistent state** — pengganti file lokal yang tidak cocok di GitHub Actions (environment ephemeral).

| Prefix di Notion / Entri Khusus | Artinya |
|---|---|
| `[MATKUL] Nama Matkul` | Mata kuliah yang sudah terdeteksi |
| `[TUGAS] Nama Tugas` | Tugas yang sudah/belum dikerjakan |
| `[DISKUSI] Diskusi.1` | Diskusi forum yang sudah/belum dikerjakan |
| `[STATUS] Matkul Komplit` | Penanda bahwa seluruh mata kuliah telah selesai diimpor (Mata Kuliah: `SYSTEM`) |
| `[STATUS] Stop` | Penanda bahwa laporan periodik sedang dinonaktifkan (Mata Kuliah: `SYSTEM`) |

---

## 🛠️ Teknologi

- **Java 25** + **Maven**
- **Moodle Web Services API** (token-based)
- **Notion API** v2022-06-28
- **Telegram Bot API**
- **GitHub Actions** (cron scheduler)
- **dotenv-java** (manajemen environment variables)

---

## 🔒 Keamanan

- File `.env` **tidak pernah** di-push ke GitHub (ada di `.gitignore`)
- Semua kredensial dikelola via **GitHub Secrets**
- Token yang sudah pernah bocor ke Git history harus di-regenerate

---

## 📋 Troubleshooting

| Masalah | Kemungkinan Penyebab | Solusi |
|---|---|---|
| Bot tidak login | NIM/PASS salah | Cek GitHub Secret `UT_NIM` dan `UT_PASS` |
| Notion error 401 | Token expired atau salah | Regenerate token di Notion Integrations |
| Notion error 400 | Database ID salah | Cek `NOTION_DATABASE_ID` |
| Status tidak update ke Selesai | Nama status bukan `Selesai` | Pastikan ada opsi `Selesai` di property Status |
| Diskusi tidak terdeteksi | Nama forum tidak diawali Diskusi/Kehadiran/Tugas | Periksa nama forum di e-learning |
| Tidak ada notif Telegram | Token atau Chat ID salah | Tes manual via Telegram API |
| Bot tidak kirim laporan | `[STATUS] Stop` masih aktif di Notion | Kirim `Run` ke bot Telegram |

---

## 📝 Lisensi

Proyek ini dibuat untuk keperluan pribadi mahasiswa Universitas Terbuka.
