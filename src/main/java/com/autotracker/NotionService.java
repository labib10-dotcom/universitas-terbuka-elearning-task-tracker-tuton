package com.autotracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Semua interaksi dengan Notion API.
 * Dipakai sebagai persistent state management agar bot tidak duplikasi data
 * walau dijalankan berkali-kali di GitHub Actions (yang tidak punya file
 * lokal).
 */
public class NotionService {

    private static final Dotenv dotenv = Dotenv.load();
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final String NOTION_API = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28";

    private static String getToken() {
        return dotenv.get("NOTION_TOKEN");
    }

    private static String getDbId() {
        return dotenv.get("NOTION_DATABASE_ID");
    }

    /**
     * Cek apakah entri dengan nama ini sudah ada di Notion (cek Name saja).
     * Dipakai untuk matkul, karena nama matkul sudah unik.
     */
    public static boolean sudahAda(String nama) {
        JSONObject titleFilter = new JSONObject();
        titleFilter.put("equals", nama);
        JSONObject condition = new JSONObject();
        condition.put("property", "Name");
        condition.put("title", titleFilter);
        JSONObject body = new JSONObject();
        body.put("filter", condition);
        return queryNotion(body) > 0;
    }

    /**
     * Cek apakah entri dengan nama + mata kuliah ini sudah ada di Notion (cek
     * keduanya).
     * Dipakai untuk diskusi dan tugas agar "Diskusi.1" dari matkul berbeda tidak
     * saling skip.
     */
    public static boolean sudahAda(String nama, String namaMatkul) {
        JSONObject nameCondition = buildTitleFilter("Name", nama);
        JSONObject matkulCondition = buildSelectFilter("Mata Kuliah", namaMatkul);

        JSONArray andArray = new JSONArray();
        andArray.put(nameCondition);
        andArray.put(matkulCondition);

        JSONObject body = new JSONObject();
        body.put("filter", new JSONObject().put("and", andArray));
        return queryNotion(body) > 0;
    }

    /**
     * Ambil semua diskusi yang BELUM selesai dari Notion.
     * Dipakai oleh laporan periodik untuk mengingatkan user via Telegram.
     * Return format: "NamaDiskusi | NamaMatkul"
     */
    public static List<String> getPendingDiskusi() {
        return getPendingByPrefix("[DISKUSI] ");
    }

    /**
     * Ambil semua tugas yang BELUM selesai dari Notion.
     * Dipakai oleh laporan periodik untuk mengingatkan user via Telegram.
     */
    public static List<String> getPendingTugas() {
        return getPendingByPrefix("[TUGAS] ");
    }

    /** Query Notion untuk entri dengan prefix tertentu yang belum Selesai */
    private static List<String> getPendingByPrefix(String prefix) {
        List<String> result = new ArrayList<>();
        try {
            String token = getToken();
            String dbId = getDbId();
            if (token == null || dbId == null)
                return result;

            JSONObject titleFilter = new JSONObject();
            titleFilter.put("starts_with", prefix);
            JSONObject nameCondition = new JSONObject();
            nameCondition.put("property", "Name");
            nameCondition.put("title", titleFilter);

            JSONObject body = new JSONObject();
            body.put("filter", nameCondition);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(NOTION_API + "/databases/" + dbId + "/query"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Notion-Version", NOTION_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JSONArray pages = new JSONObject(res.body()).getJSONArray("results");

            for (int i = 0; i < pages.length(); i++) {
                JSONObject page = pages.getJSONObject(i);
                JSONObject props = page.getJSONObject("properties");

                // Skip jika sudah Selesai
                try {
                    JSONObject statusProp = props.getJSONObject("Status");
                    if (statusProp.has("status") && !statusProp.isNull("status")) {
                        String namaStatus = statusProp.getJSONObject("status").getString("name");
                        if (namaStatus.equalsIgnoreCase("Selesai"))
                            continue;
                    }
                } catch (Exception ignored) {
                }

                // Ambil nama (hapus prefix)
                String nama = props.getJSONObject("Name")
                        .getJSONArray("title").getJSONObject(0)
                        .getJSONObject("text").getString("content")
                        .replace(prefix, "");

                // Ambil nama matkul
                String matkul = "?";
                try {
                    matkul = props.getJSONObject("Mata Kuliah")
                            .getJSONObject("select").getString("name");
                } catch (Exception ignored) {
                }

                result.add(nama + "\n   📚 " + matkul);
            }
        } catch (Exception e) {
            System.out.println("⚠️ Gagal ambil pending (" + prefix.trim() + "): " + e.getMessage());
        }

        // Dedup: jika Notion terlanjur punya entri duplikat, tampilkan hanya satu
        return new ArrayList<>(new java.util.LinkedHashSet<>(result));
    }

    /** Simpan entri baru ke Notion dan kembalikan page ID yang dihasilkan */
    public static String simpanDanAmbilId(String namaEntri, String namaMatkul) {
        try {
            String token = getToken();
            String dbId = getDbId();
            if (token == null || dbId == null)
                return null;

            JSONObject body = buildPageBody(dbId, namaEntri, namaMatkul);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(NOTION_API + "/pages"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Notion-Version", NOTION_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                String pageId = new JSONObject(res.body()).getString("id");
                System.out.println("✅ Tersimpan di Notion: " + namaEntri);
                return pageId;
            } else {
                System.out.println("❌ Gagal simpan Notion: " + res.body());
            }
        } catch (Exception e) {
            System.out.println("❌ Error Notion: " + e.getMessage());
        }
        return null;
    }

    /** Simpan entri baru ke Notion database */
    public static void simpan(String namaEntri, String namaMatkul) {
        simpanDanAmbilId(namaEntri, namaMatkul); // Reuse, cukup buang return value-nya
    }

    public static void hapusStatusMatkulKomplit() {
        arsipkanStatus("[STATUS] Matkul Komplit");
    }

    // ==========================================
    // STOP / RUN TRIGGER WORD
    // ==========================================

    /** Cek apakah [STATUS] Stop aktif di Notion → laporan periodik dinonaktifkan */
    public static boolean isStopAktif() {
        return sudahAda("[STATUS] Stop", "SYSTEM");
    }

    /** Simpan [STATUS] Stop ke Notion saat user mengirim keyword "Stop" */
    public static void simpanStatusStop() {
        if (!isStopAktif()) {
            simpan("[STATUS] Stop", "SYSTEM");
            System.out.println("💾 [STATUS] Stop disimpan ke Notion.");
        }
    }

    /** Hapus [STATUS] Stop dari Notion saat user kirim "Run" atau ada matkul baru */
    public static void hapusStatusStop() {
        arsipkanStatus("[STATUS] Stop");
    }

    /** Arsipkan (soft-delete) sebuah status marker dari Notion */
    private static void arsipkanStatus(String namaStatus) {
        String pageId = getPageId(namaStatus, "SYSTEM");
        if (pageId != null) {
            try {
                String token = getToken();
                if (token == null)
                    return;

                JSONObject body = new JSONObject();
                body.put("archived", true);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(NOTION_API + "/pages/" + pageId))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .header("Notion-Version", NOTION_VERSION)
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();

                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    System.out.println("🗑️ Berhasil menghapus " + namaStatus + " dari Notion.");
                } else {
                    System.out.println("⚠️ Gagal menghapus " + namaStatus + ": " + res.body());
                }
            } catch (Exception e) {
                System.out.println("❌ Error hapus status Notion: " + e.getMessage());
            }
        }
    }


    /** Ambil page ID dari Notion berdasarkan Name + Mata Kuliah */
    public static String getPageId(String nama, String namaMatkul) {
        try {
            String token = getToken();
            String dbId = getDbId();
            if (token == null || dbId == null)
                return null;

            JSONObject nameCondition = buildTitleFilter("Name", nama);
            JSONObject matkulCondition = buildSelectFilter("Mata Kuliah", namaMatkul);

            JSONArray andArray = new JSONArray();
            andArray.put(nameCondition);
            andArray.put(matkulCondition);

            JSONObject body = new JSONObject();
            body.put("filter", new JSONObject().put("and", andArray));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(NOTION_API + "/databases/" + dbId + "/query"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Notion-Version", NOTION_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JSONArray results = new JSONObject(res.body()).getJSONArray("results");
            if (results.length() > 0) {
                return results.getJSONObject(0).getString("id");
            }
        } catch (Exception e) {
            System.out.println("⚠️ Gagal ambil page ID: " + e.getMessage());
        }
        return null;
    }

    /** Update status page di Notion menjadi Selesai */
    public static void tandaiSelesai(String pageId) {
        try {
            String token = getToken();
            if (token == null)
                return;

            // Notion Status property (tipe "status")
            JSONObject statusValue = new JSONObject();
            statusValue.put("name", "Selesai");
            JSONObject statusProp = new JSONObject();
            statusProp.put("status", statusValue);

            JSONObject properties = new JSONObject();
            properties.put("Status", statusProp);

            JSONObject body = new JSONObject();
            body.put("properties", properties);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(NOTION_API + "/pages/" + pageId))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Notion-Version", NOTION_VERSION)
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                System.out.println("⚠️ Gagal update status Notion: " + res.body());
            }
        } catch (Exception e) {
            System.out.println("❌ Error update Notion: " + e.getMessage());
        }
    }

    // ==========================================
    // HELPER PRIVATE
    // ==========================================

    private static JSONObject buildPageBody(String dbId, String namaEntri, String namaMatkul) {
        JSONObject textObj = new JSONObject();
        textObj.put("content", namaEntri);
        JSONObject titleContent = new JSONObject();
        titleContent.put("text", textObj);
        JSONArray titleArray = new JSONArray();
        titleArray.put(titleContent);
        JSONObject nameProp = new JSONObject();
        nameProp.put("title", titleArray);

        JSONObject selectObj = new JSONObject();
        selectObj.put("name", namaMatkul);
        JSONObject matkulProp = new JSONObject();
        matkulProp.put("select", selectObj);

        JSONObject properties = new JSONObject();
        properties.put("Name", nameProp);
        properties.put("Mata Kuliah", matkulProp);

        JSONObject parent = new JSONObject();
        parent.put("database_id", dbId);

        JSONObject body = new JSONObject();
        body.put("parent", parent);
        body.put("properties", properties);
        return body;
    }

    private static int queryNotion(JSONObject body) {
        try {
            String token = getToken();
            String dbId = getDbId();
            if (token == null || dbId == null)
                return 0;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(NOTION_API + "/databases/" + dbId + "/query"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Notion-Version", NOTION_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return new JSONObject(res.body()).getJSONArray("results").length();
        } catch (Exception e) {
            System.out.println("⚠️ Gagal query Notion: " + e.getMessage());
            return 0;
        }
    }

    private static JSONObject buildTitleFilter(String property, String value) {
        JSONObject filter = new JSONObject();
        filter.put("equals", value);
        JSONObject condition = new JSONObject();
        condition.put("property", property);
        condition.put("title", filter);
        return condition;
    }

    private static JSONObject buildSelectFilter(String property, String value) {
        JSONObject filter = new JSONObject();
        filter.put("equals", value);
        JSONObject condition = new JSONObject();
        condition.put("property", property);
        condition.put("select", filter);
        return condition;
    }
}
