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
 * Pengiriman notifikasi ke Telegram Bot.
 */
public class TelegramService {

    private static final Dotenv dotenv = Dotenv.load();
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    /** Kirim pesan teks ke Telegram chat yang dikonfigurasi di .env */
    public static void kirim(String message) {
        try {
            String botToken = dotenv.get("TELEGRAM_BOT_TOKEN");
            String chatId = dotenv.get("TELEGRAM_CHAT_ID");
            if (botToken == null || chatId == null) {
                System.out.println("❌ [TELEGRAM] Token atau Chat ID tidak ditemukan di .env!");
                return;
            }

            System.out.println("📤 [TELEGRAM] Mengirim pesan ke chat " + chatId + "...");

            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("text", message);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                System.out.println("✅ [TELEGRAM] Pesan berhasil terkirim.");
            } else {
                System.out.println("❌ [TELEGRAM] Gagal kirim (HTTP " + res.statusCode() + "): " + res.body());
            }
        } catch (Exception e) {
            System.out.println("🚨 Error Telegram: " + e.getMessage());
        }
    }

    /**
     * Ambil semua keyword yang dikirim user dari Telegram dalam SATU panggilan,
     * kemudian langsung ACKNOWLEDGE semua update agar tidak muncul lagi di run
     * berikutnya.
     *
     * Urutan keyword dalam list dijaga sesuai urutan pesan — sehingga perintah
     * terakhir dari user yang berlaku (last command wins).
     *
     * @param targetKeywords Daftar keyword yang ingin dicari (case-insensitive).
     * @return List keyword yang ditemukan, dalam urutan kemunculannya di chat.
     */
    public static List<String> ambilDanAkuiKeyword(List<String> targetKeywords) {
        List<String> ditemukan = new ArrayList<>();
        try {
            String botToken = dotenv.get("TELEGRAM_BOT_TOKEN");
            String chatId   = dotenv.get("TELEGRAM_CHAT_ID");
            if (botToken == null || chatId == null) return ditemukan;

            // Ambil semua update yang belum di-acknowledge
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + botToken + "/getUpdates"))
                    .GET()
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return ditemukan;

            JSONObject json = new JSONObject(res.body());
            if (!json.has("result")) return ditemukan;

            JSONArray updates = json.getJSONArray("result");
            int maxUpdateId = -1;

            System.out.println("📬 [TELEGRAM] " + updates.length() + " update ditemukan.");

            for (int i = 0; i < updates.length(); i++) {
                JSONObject update = updates.getJSONObject(i);
                int updateId = update.optInt("update_id", -1);
                if (updateId > maxUpdateId) maxUpdateId = updateId;

                if (!update.has("message")) continue;
                JSONObject message = update.getJSONObject("message");
                if (!message.has("chat") || !message.has("text")) continue;

                long msgChatId = message.getJSONObject("chat").getLong("id");
                if (!String.valueOf(msgChatId).equals(chatId)) continue;

                String text = message.getString("text").trim();
                for (String kw : targetKeywords) {
                    // Cocokkan case-insensitive, dengan atau tanpa tanda seru
                    if (text.equalsIgnoreCase(kw) || text.equalsIgnoreCase(kw.replace("!", ""))) {
                        ditemukan.add(kw.toLowerCase());
                        System.out.println("   📥 Keyword terdeteksi: '" + text + "' (update_id=" + updateId + ")");
                        break;
                    }
                }
            }

            // Acknowledge SEMUA update agar tidak diproses ulang di run berikutnya
            if (maxUpdateId >= 0) {
                int offset = maxUpdateId + 1;
                HttpRequest ackReq = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.telegram.org/bot" + botToken
                                + "/getUpdates?offset=" + offset))
                        .GET()
                        .build();
                httpClient.send(ackReq, HttpResponse.BodyHandlers.ofString());
                System.out.println("✅ [TELEGRAM] Semua update di-acknowledge (offset=" + offset + ").");
            }

        } catch (Exception e) {
            System.out.println("⚠️ Gagal ambil keyword Telegram: " + e.getMessage());
        }
        return ditemukan;
    }
}
