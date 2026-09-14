package com.autotracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Semua interaksi dengan API e-learning Moodle Universitas Terbuka.
 */
public class MoodleService {

    private static final Dotenv dotenv = Dotenv.load();
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final String BASE_URL = "https://elearning.ut.ac.id";
    private static final String NIM = dotenv.get("UT_NIM");
    private static final String PASS = dotenv.get("UT_PASS");



    /** Login ke Moodle dan dapatkan token API */
    public static String getToken() {
        String url = BASE_URL + "/login/token.php?username=" + NIM + "&password=" + PASS + "&service=moodle_mobile_app";
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(res.body());
            if (json.has("token")) return json.getString("token");
        } catch (Exception e) {
            System.out.println("❌ Gagal login API: " + e.getMessage());
        }
        return null;
    }

    /** Ambil user ID dari profil Moodle */
    public static int getUserId(String token) {
        String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                + "&wsfunction=core_webservice_get_site_info&moodlewsrestformat=json";
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(res.body());
            if (json.has("userid")) return json.getInt("userid");
        } catch (Exception e) {
            System.out.println("❌ Gagal ambil profil: " + e.getMessage());
        }
        return -1;
    }

    /** Ambil daftar mata kuliah yang diikuti user */
    public static JSONArray getDaftarMatkul(String token, int userId) {
        String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                + "&wsfunction=core_enrol_get_users_courses&moodlewsrestformat=json&userid=" + userId;
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return new JSONArray(res.body());
        } catch (Exception e) {
            System.out.println("❌ Gagal narik Mata Kuliah: " + e.getMessage());
            return null;
        }
    }

    /** Buat peta courseId -> namaMatkul untuk lookup cepat */
    public static Map<Integer, String> buildCourseMap(JSONArray daftarMatkul) {
        Map<Integer, String> map = new HashMap<>();
        for (int i = 0; i < daftarMatkul.length(); i++) {
            JSONObject m = daftarMatkul.getJSONObject(i);
            map.put(m.getInt("id"), m.getString("fullname"));
        }
        return map;
    }

    /**
     * Deteksi otomatis apakah suatu course adalah matkul Praktik,
     * berdasarkan nama seksi/topik di dalam kursus tersebut:
     *   - Praktik  : seksi bernama "Aktivitas Belajar X"
     *   - Reguler  : seksi bernama "Sesi X"
     *
     * Menggunakan API core_course_get_contents dengan excludemodules=1
     * agar response ringan (hanya ambil nama seksi, tanpa isi modul).
     */
    private static boolean isPraktikCourse(String token, int courseId) {
        String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                + "&wsfunction=core_course_get_contents"
                + "&moodlewsrestformat=json"
                + "&courseid=" + courseId
                + "&options[0][name]=excludemodules&options[0][value]=1";
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JSONArray sections = new JSONArray(res.body());
            for (int i = 0; i < sections.length(); i++) {
                String sectionName = sections.getJSONObject(i).optString("name", "");
                if (sectionName.toLowerCase().contains("aktivitas belajar")) {
                    return true;
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ Gagal cek tipe course " + courseId + ": " + e.getMessage());
        }
        return false;

    }

    /**
     * Bangun set courseId yang terdeteksi sebagai matkul Praktik.
     * Deteksi otomatis via nama section — tidak perlu konfigurasi manual.
     */
    public static Set<Integer> getPraktikCourseIds(String token, JSONArray daftarMatkul) {
        Set<Integer> ids = new HashSet<>();
        System.out.println("\n🔬 Mendeteksi tipe matkul (Praktik vs Reguler)...");
        for (int i = 0; i < daftarMatkul.length(); i++) {
            JSONObject m = daftarMatkul.getJSONObject(i);
            int courseId    = m.getInt("id");
            String fullname = m.optString("fullname", "?");

            if (isPraktikCourse(token, courseId)) {
                ids.add(courseId);
                System.out.println("   🔬 PRAKTIK (Aktivitas Belajar): " + fullname);
            } else {
                System.out.println("   📚 Reguler  (Sesi)           : " + fullname);
            }
        }
        return ids;
    }

    /**
     * Ambil semua assignment (Tugas) dari semua course.
     * Menggunakan mod_assign_get_assignments — tidak ada limit, khusus assign.
     * Tiap objek assignment di-inject field "_courseId" untuk lookup course map.
     */
    public static JSONArray getAssignments(String token, JSONArray daftarMatkul) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL + "/webservice/rest/server.php?wstoken=" + token);
        url.append("&wsfunction=mod_assign_get_assignments");
        url.append("&moodlewsrestformat=json");

        for (int i = 0; i < daftarMatkul.length(); i++) {
            url.append("&courseids[").append(i).append("]=")
               .append(daftarMatkul.getJSONObject(i).getInt("id"));
        }

        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url.toString())).GET().build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JSONObject json = new JSONObject(res.body());

        // Flatten: courses[].assignments[] → satu JSONArray semua assignment
        JSONArray allAssignments = new JSONArray();
        JSONArray courses = json.optJSONArray("courses");
        if (courses == null) return allAssignments;

        for (int i = 0; i < courses.length(); i++) {
            JSONObject course = courses.getJSONObject(i);
            int courseId = course.getInt("id");
            JSONArray assignments = course.optJSONArray("assignments");
            if (assignments == null) continue;

            for (int j = 0; j < assignments.length(); j++) {
                JSONObject assign = assignments.getJSONObject(j);
                assign.put("_courseId", courseId); // inject untuk lookup courseMap
                allAssignments.put(assign);
            }
        }
        return allAssignments;
    }

    /**
     * Ambil daftar percakapan private dari Moodle Messaging.
     * Digunakan untuk mendeteksi pesan baru dari dosen yang belum dibaca.
     * type=1 = individual/private, limitnum=50 cukup untuk semua dosen.
     */
    public static JSONArray getPesanMasuk(String token, int userId) throws Exception {
        String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                + "&wsfunction=core_message_get_conversations"
                + "&moodlewsrestformat=json"
                + "&userid=" + userId
                + "&type=1"       // 1 = private/individual
                + "&limitnum=50";
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JSONObject json = new JSONObject(res.body());
        return json.optJSONArray("conversations");
    }

    /**
     * Cek status submission tugas tertentu untuk user tertentu.
     * Digunakan sebagai fallback untuk matkul reguler yang tidak punya activity completion.
     *
     * @return "submitted" jika sudah dikumpulkan, "nosubmission" jika belum/error.
     */
    public static String getSubmissionStatus(String token, int assignId, int userId) {
        try {
            String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                    + "&wsfunction=mod_assign_get_submission_status"
                    + "&moodlewsrestformat=json"
                    + "&assignid=" + assignId
                    + "&userid=" + userId;
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(res.body());

            // Struktur: { lastattempt: { submission: { status: "submitted" | ... } } }
            JSONObject lastAttempt = json.optJSONObject("lastattempt");
            if (lastAttempt == null) return "nosubmission";
            JSONObject submission = lastAttempt.optJSONObject("submission");
            if (submission == null) return "nosubmission";
            return submission.optString("status", "nosubmission");
        } catch (Exception e) {
            System.out.println("   ⚠️ Gagal cek submission status assignId=" + assignId + ": " + e.getMessage());
            return "nosubmission";
        }
    }

    /** Ambil semua forum dari daftar course */
    public static JSONArray getForumsByCourses(String token, JSONArray daftarMatkul) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL + "/webservice/rest/server.php?wstoken=" + token);
        url.append("&wsfunction=mod_forum_get_forums_by_courses");
        url.append("&moodlewsrestformat=json");

        for (int i = 0; i < daftarMatkul.length(); i++) {
            url.append("&courseids[").append(i).append("]=")
               .append(daftarMatkul.getJSONObject(i).getInt("id"));
        }

        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url.toString())).GET().build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return new JSONArray(res.body());
    }

    /** Ambil semua diskusi dari satu forum */
    public static JSONArray getForumDiscussions(String token, int forumId) throws Exception {
        String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                + "&wsfunction=mod_forum_get_forum_discussions&moodlewsrestformat=json&forumid=" + forumId;
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JSONObject json = new JSONObject(res.body());
        return json.has("discussions") ? json.getJSONArray("discussions") : new JSONArray();
    }

    /**
     * Filter relevansi forum untuk matkul reguler (TUTON).
     * Diambil: Diskusi.X, Kehadiran Sesi, dan Tugas.
     */
    public static boolean isForumRelevan(String namaForum) {
        return isForumRelevan(namaForum, false);
    }

    /**
     * Filter relevansi forum dengan mempertimbangkan tipe matkul.
     * - Matkul reguler (TUTON): Diskusi + Kehadiran + Tugas
     * - Matkul Praktik       : HANYA Tugas (Diskusi & Kehadiran dilewati)
     */
    public static boolean isForumRelevan(String namaForum, boolean isPraktikCourse) {
        String lower = namaForum.toLowerCase();
        if (isPraktikCourse) {
            // Matkul Praktik tidak punya diskusi wajib seperti TUTON,
            // cukup track Tugas saja.
            return lower.startsWith("tugas");
        }
        // Gunakan contains("diskusi") agar menangkap nama seperti "Forum Diskusi.1"
        // yang tidak diawali tapi mengandung kata "diskusi"
        return lower.contains("diskusi") ||
               lower.startsWith("kehadiran") ||
               lower.startsWith("tugas");
    }

    /**
     * Cek apakah user sudah pernah membalas/berpartisipasi di suatu diskusi.
     * Dipakai sebagai FALLBACK jika cmid forum tidak tersedia.
     */
    public static boolean sudahBerpartisipasi(String token, int discussionId, int userId) {
        String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                + "&wsfunction=mod_forum_get_discussion_posts"
                + "&moodlewsrestformat=json"
                + "&discussionid=" + discussionId;
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(res.body());
            if (!json.has("posts")) return false;

            JSONArray posts = json.getJSONArray("posts");
            for (int i = 0; i < posts.length(); i++) {
                if (posts.getJSONObject(i).getInt("userid") == userId) {
                    return true;
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ Gagal cek partisipasi diskusi " + discussionId + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * Ambil completion status semua aktivitas di satu course.
     * Ini adalah sumber data yang SAMA dengan tanda hijau "✓ Done" di UI Moodle.
     *
     * Return: Map dari cmid -> state (0=belum selesai, 1=selesai, 2=pass, 3=fail)
     * cmid adalah ID course module — tiap forum punya cmid sendiri.
     */
    public static Map<Integer, Integer> getCompletionStatus(String token, int courseId, int userId) {
        Map<Integer, Integer> completionMap = new HashMap<>();
        String url = BASE_URL + "/webservice/rest/server.php?wstoken=" + token
                + "&wsfunction=core_completion_get_activities_completion_status"
                + "&moodlewsrestformat=json"
                + "&courseid=" + courseId
                + "&userid=" + userId;
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(res.body());
            if (!json.has("statuses")) return completionMap;

            JSONArray statuses = json.getJSONArray("statuses");
            for (int i = 0; i < statuses.length(); i++) {
                JSONObject s = statuses.getJSONObject(i);
                completionMap.put(s.getInt("cmid"), s.getInt("state"));
            }
        } catch (Exception e) {
            System.out.println("⚠️ Gagal ambil completion status course " + courseId + ": " + e.getMessage());
        }
        return completionMap;
    }
}
