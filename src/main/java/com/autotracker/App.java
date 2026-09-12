package com.autotracker;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry point utama bot tracker e-learning UT.
 * Orkestrasi 4 pengecekan: Matkul Baru, Tugas, Diskusi Forum, dan Pesan Dosen.
 */
public class App {

    private static boolean adaMatkulBaru = false;
    private static boolean adaTugasBaru = false;
    private static boolean adaDiskusiBaru = false;
    private static boolean adaPesanBaru = false;

    public static void main(String[] args) {
        System.out.println("\n⏳ [" + java.time.LocalTime.now() + "] Bot bangun! Mengecek e-learning...");

        // Cek apakah user mengirim "Matkul Komplit!" di Telegram
        if (TelegramService.cekKeyword("Matkul Komplit!")) {
            System.out.println("📥 Terdeteksi keyword 'Matkul Komplit!' dari Telegram.");
            if (!NotionService.sudahAda("[STATUS] Matkul Komplit", "SYSTEM")) {
                NotionService.simpan("[STATUS] Matkul Komplit", "SYSTEM");
                System.out.println("💾 Menyimpan [STATUS] Matkul Komplit ke Notion.");
                TelegramService.kirim("🆗 Status 'Matkul Komplit!' terdeteksi. Notifikasi pembaharuan mata kuliah telah dinonaktifkan.");
            }
        }

        String token = MoodleService.getToken();
        if (token == null) {
            System.out.println("❌ Gagal login. Bot berhenti.");
            return;
        }

        int userId = MoodleService.getUserId(token);
        if (userId == -1) {
            System.out.println("❌ Gagal ambil profil. Bot berhenti.");
            return;
        }

        JSONArray daftarMatkul = MoodleService.getDaftarMatkul(token, userId);
        if (daftarMatkul == null || daftarMatkul.isEmpty()) {
            System.out.println("📭 Belum ada mata kuliah aktif. Bot tidur.");
            // Reset semua status saat semester berakhir (tidak ada matkul aktif)
            NotionService.hapusStatusMatkulKomplit();
            NotionService.hapusStatusEndSession();
            return;
        }


        // Buat peta courseId -> namaMatkul untuk lookup cepat di semua pengecekan
        Map<Integer, String> courseMap = MoodleService.buildCourseMap(daftarMatkul);

        cekMatkulBaru(daftarMatkul);
        cekTugasBaru(token, daftarMatkul, courseMap, userId);
        cekDiskusiBaru(token, daftarMatkul, courseMap, userId);
        cekPesanDosen(token, userId);

        // Cek apakah semua tugas sudah selesai di Notion
        List<String> pendingTugas = NotionService.getPendingTugas();
        List<String> pendingDiskusi = NotionService.getPendingDiskusi();
        boolean semuaSelesai = pendingTugas.isEmpty() && pendingDiskusi.isEmpty();
        boolean adaDataBaru = adaMatkulBaru || adaTugasBaru || adaDiskusiBaru || adaPesanBaru;

        // Cek apakah run INI mendeteksi sesi akhir dari elearning
        boolean endSessionRunIni = MoodleService.isEndSessionReached();

        // ── DIAGNOSTIK ────────────────────────────────────────────────
        System.out.println("\n🔎 [DIAGNOSTIK] Status akhir run:");
        System.out.println("   pendingTugas   : " + pendingTugas.size() + " item");
        System.out.println("   pendingDiskusi : " + pendingDiskusi.size() + " item");
        System.out.println("   semuaSelesai   : " + semuaSelesai);
        System.out.println("   adaDataBaru    : " + adaDataBaru
                + " (matkul=" + adaMatkulBaru + ", tugas=" + adaTugasBaru
                + ", diskusi=" + adaDiskusiBaru + ", pesan=" + adaPesanBaru + ")");
        System.out.println("   endSessionRunIni: " + endSessionRunIni);
        // ─────────────────────────────────────────────────────────────

        if (endSessionRunIni) {
            // Sesi akhir masih ada di elearning → simpan/pertahankan flag di Notion
            NotionService.simpanStatusEndSession();
            System.out.println("🔒 Sesi akhir dikonfirmasi dari elearning. Flag dipertahankan di Notion.");
        } else {
            // Run ini TIDAK mendeteksi sesi akhir → semester baru / kursus baru
            // Hapus flag lama dari Notion agar laporan kembali berjalan
            if (NotionService.isEndSessionTersimpan()) {
                System.out.println("🔄 Elearning tidak lagi menampilkan Sesi 8/AB-15 — mereset flag End Session.");
                NotionService.hapusStatusEndSession();
            }
        }

        // Final: laporan dihentikan HANYA jika sesi akhir terdeteksi di run INI
        if (endSessionRunIni && semuaSelesai && !adaDataBaru) {
            System.out.println("🤫 Sesi akhir (Sesi 8/Aktivitas 15) terdeteksi, semua tugas selesai, dan tidak ada data baru. Melewati laporan periodik.");
        } else {
            System.out.println("📊 Kondisi laporan: endSession=" + endSessionRunIni
                    + " | semuaSelesai=" + semuaSelesai + " | adaDataBaru=" + adaDataBaru
                    + " → Mengirim laporan...");
            kirimRingkasanPeriodik(pendingTugas, pendingDiskusi);
        }

        System.out.println("\n💤 Pengecekan selesai. Bot tidur lagi...");
    }


    // ==========================================
    // CEK 1: Matkul Baru (awal semester)
    // ==========================================

    private static void cekMatkulBaru(JSONArray daftarMatkul) {
        System.out.println("\n📋 [CEK 1] Memeriksa matkul baru...");
        List<String> matkulBaru = new ArrayList<>();

        for (int i = 0; i < daftarMatkul.length(); i++) {
            JSONObject matkul = daftarMatkul.getJSONObject(i);
            String nama = matkul.getString("fullname");
            String penanda = "[MATKUL] " + nama;

            if (!NotionService.sudahAda(penanda)) {
                matkulBaru.add(nama);
                System.out.println("🆕 Matkul BARU: " + nama);
                NotionService.simpan(penanda, nama);
            } else {
                System.out.println("⏭️  Skip matkul: " + nama);
            }
        }

        boolean isMatkulKomplit = NotionService.sudahAda("[STATUS] Matkul Komplit", "SYSTEM");
        if (!matkulBaru.isEmpty()) {
            if (!isMatkulKomplit) {
                adaMatkulBaru = true;
                StringBuilder pesan = new StringBuilder("📚 Semester Baru! " + matkulBaru.size() + " matkul aktif:\n");
                for (String m : matkulBaru)
                    pesan.append("• ").append(m).append("\n");
                TelegramService.kirim(pesan.toString().trim());
            } else {
                System.out.println("🤫 Notifikasi matkul baru dilewati karena status 'Matkul Komplit!' aktif.");
            }
        }
    }

    // ==========================================
    // CEK 2: Tugas & Kuis (via Calendar API)
    // ==========================================

    private static void cekTugasBaru(String token, JSONArray daftarMatkul, Map<Integer, String> courseMap, int userId) {
        System.out.println("\n📡 [CEK 2] Memeriksa tugas (via mod_assign_get_assignments)...");
        List<String> tugasBaru = new ArrayList<>();
        long sekarang = java.time.Instant.now().getEpochSecond();
        int selesaiDiupdate = 0;

        try {
            JSONArray assignments = MoodleService.getAssignments(token, daftarMatkul);
            if (assignments.isEmpty()) {
                System.out.println("📭 Tidak ada assignment ditemukan.");
                return;
            }

            // Bangun completion map per course (sama seperti CEK 3 diskusi)
            System.out.println("🔄 Memuat completion status untuk assignment...");
            Map<Integer, Map<Integer, Integer>> completionByCourse = new HashMap<>();
            for (int i = 0; i < daftarMatkul.length(); i++) {
                int cid = daftarMatkul.getJSONObject(i).getInt("id");
                completionByCourse.put(cid, MoodleService.getCompletionStatus(token, cid, userId));
            }

            System.out.println("🔍 Ditemukan " + assignments.length() + " assignment. Mengecek...");
            Set<String> simpanDalamRun = new HashSet<>(); // anti-duplikat dalam satu run
            for (int i = 0; i < assignments.length(); i++) {
                JSONObject assign = assignments.getJSONObject(i);
                String namaTugas = assign.optString("name", "?");
                int courseId = assign.optInt("_courseId", -1);
                String namaMatkul = courseMap.getOrDefault(courseId, "Matkul Tidak Diketahui");
                long duedate = assign.optLong("duedate", 0);
                long allowFrom = assign.optLong("allowsubmissionsfromdate", 0);
                int cmid = assign.optInt("cmid", -1);
                int assignId = assign.optInt("id", -1);

                // Filter: skip jika belum dibuka atau deadline sudah lewat
                boolean sudahBuka = allowFrom == 0 || allowFrom <= sekarang;
                boolean belumLewat = duedate == 0 || duedate > sekarang;
                if (!sudahBuka || !belumLewat)
                    continue;

                // Cek pola sesi akhir dari nama tugas
                if (MoodleService.checkEndSessionPattern(namaTugas)) {
                    MoodleService.setEndSessionReached(true);
                }

                // Cek 1: completion status via cmid (Praktik: "Mark as Done" button)
                Map<Integer, Integer> completionMap = completionByCourse.getOrDefault(courseId, new HashMap<>());
                boolean sudahSelesai = cmid != -1 && completionMap.getOrDefault(cmid, 0) >= 1;

                // Cek 2: fallback via submission status (Reguler: cek apakah file sudah
                // dikumpulkan)
                if (!sudahSelesai && assignId != -1) {
                    String subStatus = MoodleService.getSubmissionStatus(token, assignId, userId);
                    sudahSelesai = "submitted".equalsIgnoreCase(subStatus);
                    System.out.println("   📤 Submission status '" + namaTugas + "': " + subStatus);
                }

                String penanda = "[TUGAS] " + namaTugas;
                System.out.println("   📝 '" + namaTugas + "' | " + namaMatkul
                        + " | selesai=" + sudahSelesai);

                if (NotionService.sudahAda(penanda, namaMatkul)) {
                    // Sudah ada → update ke Selesai jika completion terpenuhi
                    if (sudahSelesai) {
                        String pageId = NotionService.getPageId(penanda, namaMatkul);
                        if (pageId != null) {
                            NotionService.tandaiSelesai(pageId);
                            selesaiDiupdate++;
                            System.out.println("   ✅ Diupdate ke Selesai: " + namaTugas + " | " + namaMatkul);
                        }
                    } else {
                        System.out.println("   ⏭️  Belum dikerjakan: " + namaTugas + " | " + namaMatkul);
                    }
                    continue;
                }

                // Belum ada di Notion — simpan dengan status awal
                String kunciRun = penanda + "||" + namaMatkul;
                if (sudahSelesai) {
                    if (simpanDalamRun.add(kunciRun)) {
                        System.out.println("   ✅ Sudah selesai, simpan sebagai Selesai: " + namaTugas);
                        String pageId = NotionService.simpanDanAmbilId(penanda, namaMatkul);
                        if (pageId != null)
                            NotionService.tandaiSelesai(pageId);
                    } else {
                        System.out.println("   🛡️ Duplikat dicegah (in-run): " + penanda);
                    }
                } else {
                    if (simpanDalamRun.add(kunciRun)) {
                        tugasBaru.add(namaTugas + "\n   📚 " + namaMatkul);
                        System.out.println("   👉 Tugas BARU: " + namaTugas + " | " + namaMatkul);
                        NotionService.simpan(penanda, namaMatkul);
                    } else {
                        System.out.println("   🛡️ Duplikat dicegah (in-run): " + penanda);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Gagal cek tugas: " + e.getMessage());
            e.printStackTrace();
        }

        if (!tugasBaru.isEmpty()) {
            adaTugasBaru = true;
            StringBuilder pesan = new StringBuilder("🚨 Ada " + tugasBaru.size() + " Tugas BARU!\n\n");
            for (String t : tugasBaru)
                pesan.append("• ").append(t).append("\n");
            TelegramService.kirim(pesan.toString().trim());
        } else {
            System.out.println("💤 Tidak ada tugas baru.");
        }
        if (selesaiDiupdate > 0) {
            System.out.println("📝 " + selesaiDiupdate + " tugas diupdate ke Selesai di Notion.");
        }
    }

    // ==========================================
    // CEK 4: Pesan Dosen (via Messaging API)
    // ==========================================

    /**
     * Cek percakapan private dari Moodle.
     * Kirim notif Telegram jika ada pesan belum terbaca dari dosen.
     *
     * Tidak perlu Notion — Moodle sendiri tracking status baca via unreadcount.
     * Begitu user buka pesannya di Moodle → unreadcount=0 → bot berhenti notif.
     */
    private static void cekPesanDosen(String token, int userId) {
        System.out.println("\n\ud83d\udce8 [CEK 4] Memeriksa pesan masuk dari dosen...");
        List<String> pesanBaru = new ArrayList<>();

        try {
            JSONArray conversations = MoodleService.getPesanMasuk(token, userId);
            if (conversations == null || conversations.isEmpty()) {
                System.out.println("📭 Tidak ada percakapan.");
                return;
            }

            System.out.println("🔍 Ditemukan " + conversations.length() + " percakapan. Mengecek...");
            for (int i = 0; i < conversations.length(); i++) {
                JSONObject conv = conversations.getJSONObject(i);
                int unread = conv.optInt("unreadcount", 0);
                if (unread == 0)
                    continue; // skip jika semua sudah terbaca

                JSONArray messages = conv.optJSONArray("messages");
                if (messages == null || messages.isEmpty())
                    continue;

                JSONObject lastMsg = messages.getJSONObject(0);
                int senderUserId = lastMsg.optInt("useridfrom", -1);
                if (senderUserId == userId)
                    continue; // skip jika pesan dari kita sendiri

                // Ambil nama pengirim dari members (filter keluar diri sendiri)
                String namaDosen = "Dosen";
                JSONArray members = conv.optJSONArray("members");
                if (members != null) {
                    for (int m = 0; m < members.length(); m++) {
                        JSONObject member = members.getJSONObject(m);
                        if (member.optInt("id", -1) != userId) {
                            namaDosen = member.optString("fullname", "Dosen");
                            break;
                        }
                    }
                }

                // Bersihkan HTML dari isi pesan & potong jika terlalu panjang
                String isiPesan = lastMsg.optString("text", "")
                        .replaceAll("<[^>]*>", "")
                        .replaceAll("&nbsp;", " ")
                        .replaceAll("&amp;", "&")
                        .trim();
                if (isiPesan.length() > 200)
                    isiPesan = isiPesan.substring(0, 200) + "...";

                pesanBaru.add("👤 " + namaDosen + "\n   💬 \"" + isiPesan + "\"");
                System.out.println("   📨 Pesan belum dibaca dari: " + namaDosen + " (" + unread + " pesan)");
            }

        } catch (Exception e) {
            System.out.println("❌ Gagal cek pesan dosen: " + e.getMessage());
            e.printStackTrace();
        }

        if (!pesanBaru.isEmpty()) {
            adaPesanBaru = true;
            StringBuilder pesan = new StringBuilder(
                    "📨 Ada " + pesanBaru.size() + " Pesan Belum Dibaca dari Dosen!\n\n");
            for (String p : pesanBaru)
                pesan.append(p).append("\n\n");
            pesan.append("👆 Buka Moodle untuk membaca & menghentikan pengingat ini.");
            TelegramService.kirim(pesan.toString().trim());
        } else {
            System.out.println("💤 Tidak ada pesan baru dari dosen.");
        }
    }

    // ==========================================
    // LAPORAN PERIODIK (selalu jalan tiap run)
    // ==========================================

    /**
     * Selalu kirim summary ke Telegram setiap bot jalan.
     * Berisi daftar diskusi yang masih belum dikerjakan sebagai pengingat.
     */
    private static void kirimRingkasanPeriodik(List<String> pendingTugas, List<String> pendingDiskusi) {
        System.out.println("\n📊 [LAPORAN] Menyiapkan ringkasan periodik...");

        StringBuilder pesan = new StringBuilder();
        pesan.append("📊 Laporan Bot UT | ").append(java.time.LocalDate.now()).append("\n");
        pesan.append("\u23f0 ").append(java.time.LocalTime.now().withNano(0)).append("\n\n");

        // Seksi Tugas
        if (pendingTugas.isEmpty()) {
            pesan.append("✅ Semua tugas sudah dikerjakan!\n\n");
        } else {
            pesan.append("📝 ").append(pendingTugas.size()).append(" Tugas belum dikerjakan:\n");
            for (String item : pendingTugas)
                pesan.append("• ").append(item).append("\n");
            pesan.append("\n");
        }

        // Seksi Diskusi
        if (pendingDiskusi.isEmpty()) {
            pesan.append("✅ Semua diskusi sudah selesai!");
        } else {
            pesan.append("💬 ").append(pendingDiskusi.size()).append(" Diskusi belum dikerjakan:\n");
            for (String item : pendingDiskusi)
                pesan.append("• ").append(item).append("\n");
            pesan.append("\n💡 Yuk segera dikerjain sebelum deadline!");
        }

        TelegramService.kirim(pesan.toString().trim());
    }

    // ==========================================
    // CEK 3: Diskusi Forum (via Forum API)
    // ==========================================

    private static void cekDiskusiBaru(String token, JSONArray daftarMatkul, Map<Integer, String> courseMap,
            int userId) {
        System.out.println("\n💬 [CEK 3] Memeriksa diskusi forum...");
        List<String> diskusiBaru = new ArrayList<>();
        int selesaiDiupdate = 0;

        try {
            JSONArray forums = MoodleService.getForumsByCourses(token, daftarMatkul);

            if (forums.isEmpty()) {
                System.out.println("📭 Tidak ada forum ditemukan.");
                return;
            }

            // Bangun completion map SEKALI per course
            System.out.println("🔄 Memuat Activity Completion status dari Moodle...");
            Map<Integer, Map<Integer, Integer>> completionByCourse = new HashMap<>();
            for (int i = 0; i < daftarMatkul.length(); i++) {
                int courseId = daftarMatkul.getJSONObject(i).getInt("id");
                completionByCourse.put(courseId, MoodleService.getCompletionStatus(token, courseId, userId));
            }

            // Deteksi matkul Praktik sekali di awal — otomatis via nama section course
            Set<Integer> praktikCourseIds = MoodleService.getPraktikCourseIds(token, daftarMatkul);
            if (!praktikCourseIds.isEmpty()) {
                System.out.println("🔬 " + praktikCourseIds.size()
                        + " matkul Praktik terdeteksi — hanya forum Tugas yang diambil.");
            }

            System.out.println("📂 Ditemukan " + forums.length() + " forum. Mengecek diskusi...");
            Set<String> simpanDalamRun = new HashSet<>(); // anti-duplikat dalam satu run
            for (int f = 0; f < forums.length(); f++) {
                JSONObject forum = forums.getJSONObject(f);
                int forumId = forum.getInt("id");
                int courseId = forum.getInt("course");
                int cmid = forum.optInt("cmid", -1);
                String namaMatkul = courseMap.getOrDefault(courseId, "Matkul Tidak Diketahui");
                boolean isPraktik = praktikCourseIds.contains(courseId);

                // Cek pola sesi akhir dari nama forum
                String forumName = forum.optString("name", "");
                if (MoodleService.checkEndSessionPattern(forumName)) {
                    MoodleService.setEndSessionReached(true);
                }

                // Tentukan status selesai via Moodle completion (= tanda hijau Done di UI)
                // State: 0=belum, 1=selesai, 2=selesai(pass), 3=selesai(fail)
                boolean sudahDikerjakan;
                if (cmid != -1) {
                    Map<Integer, Integer> completionMap = completionByCourse.getOrDefault(courseId, new HashMap<>());
                    int state = completionMap.getOrDefault(cmid, 0);
                    sudahDikerjakan = state >= 1;
                    System.out.println("🔍 Forum '" + forum.optString("name", "?") + "' completion state: " + state);
                } else {
                    // Fallback: cek via posts jika cmid tidak tersedia
                    sudahDikerjakan = false; // akan di-resolve per discussion di bawah
                }

                JSONArray discussions = MoodleService.getForumDiscussions(token, forumId);

                // ── DEBUG: tampilkan semua forum + diskusi sebelum difilter ──────────
                System.out.println("📋 Forum: '" + forum.optString("name", "?") + "'"
                        + " | " + (isPraktik ? "PRAKTIK" : "reguler")
                        + " | " + namaMatkul
                        + " | " + discussions.length() + " diskusi");
                for (int d = 0; d < discussions.length(); d++) {
                    System.out.println("   → Diskusi[" + d + "]: '"
                            + discussions.getJSONObject(d).optString("name", "?") + "'");
                }
                // ─────────────────────────────────────────────────────────────────────

                for (int d = 0; d < discussions.length(); d++) {
                    JSONObject disc = discussions.getJSONObject(d);
                    String namaDiskusi = disc.getString("name");

                    // Cek pola sesi akhir dari nama diskusi
                    if (MoodleService.checkEndSessionPattern(namaDiskusi)) {
                        MoodleService.setEndSessionReached(true);
                    }
                    int discussionId = disc.getInt("id");

                    if (!MoodleService.isForumRelevan(namaDiskusi, isPraktik)) {
                        System.out.println("🚫 Skip (" + (isPraktik ? "Praktik→bukan Tugas" : "tidak relevan") + "): "
                                + namaDiskusi);
                        continue;
                    }

                    // Jika cmid tidak tersedia, fallback ke cek posts
                    boolean finalSelesai = (cmid != -1)
                            ? sudahDikerjakan
                            : MoodleService.sudahBerpartisipasi(token, discussionId, userId);

                    String penanda = "[DISKUSI] " + namaDiskusi;

                    if (NotionService.sudahAda(penanda, namaMatkul)) {
                        if (finalSelesai) {
                            String pageId = NotionService.getPageId(penanda, namaMatkul);
                            if (pageId != null) {
                                NotionService.tandaiSelesai(pageId);
                                selesaiDiupdate++;
                                System.out.println("✅ Diupdate ke Selesai: " + namaDiskusi + " | " + namaMatkul);
                            }
                        } else {
                            System.out.println("⏭️  Belum dikerjakan: " + namaDiskusi + " | " + namaMatkul);
                        }
                        continue;
                    }

                    // Belum ada di Notion
                    String kunciRun = penanda + "||" + namaMatkul;
                    if (finalSelesai) {
                        if (simpanDalamRun.add(kunciRun)) {
                            System.out.println("✅ Sudah selesai, simpan sebagai Selesai: " + namaDiskusi);
                            String pageId = NotionService.simpanDanAmbilId(penanda, namaMatkul);
                            if (pageId != null)
                                NotionService.tandaiSelesai(pageId);
                        } else {
                            System.out.println("   🛡️ Duplikat dicegah (in-run): " + penanda);
                        }
                    } else {
                        if (simpanDalamRun.add(kunciRun)) {
                            diskusiBaru.add(namaDiskusi + "\n   📚 " + namaMatkul);
                            System.out
                                    .println("💬 Diskusi BARU (belum dikerjakan): " + namaDiskusi + " | " + namaMatkul);
                            NotionService.simpan(penanda, namaMatkul);
                        } else {
                            System.out.println("   🛡️ Duplikat dicegah (in-run): " + penanda);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Gagal cek diskusi: " + e.getMessage());
        }

        if (!diskusiBaru.isEmpty()) {
            adaDiskusiBaru = true;
            StringBuilder pesan = new StringBuilder("💬 Ada " + diskusiBaru.size() + " Diskusi BARU!\n\n");
            for (String d : diskusiBaru)
                pesan.append("• ").append(d).append("\n");
            TelegramService.kirim(pesan.toString().trim());
        } else {
            System.out.println("💤 Tidak ada diskusi baru.");
        }

        if (selesaiDiupdate > 0) {
            System.out.println("📝 " + selesaiDiupdate + " diskusi diupdate ke Selesai di Notion.");
        }
    }
}