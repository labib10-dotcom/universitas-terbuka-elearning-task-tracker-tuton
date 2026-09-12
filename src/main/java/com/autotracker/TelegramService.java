package com.autotracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    /** Cek apakah user mengirimkan keyword di Telegram */
    public static boolean cekKeyword(String keyword) {
        try {
            String botToken = dotenv.get("TELEGRAM_BOT_TOKEN");
            String chatId = dotenv.get("TELEGRAM_CHAT_ID");
            if (botToken == null || chatId == null) return false;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + botToken + "/getUpdates"))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JSONObject json = new JSONObject(res.body());
                if (json.has("result")) {
                    JSONArray updates = json.getJSONArray("result");
                    for (int i = 0; i < updates.length(); i++) {
                        JSONObject update = updates.getJSONObject(i);
                        if (update.has("message")) {
                            JSONObject message = update.getJSONObject("message");
                            if (message.has("chat") && message.has("text")) {
                                JSONObject chat = message.getJSONObject("chat");
                                long msgChatId = chat.getLong("id");
                                if (String.valueOf(msgChatId).equals(chatId)) {
                                    String text = message.getString("text").trim();
                                    // Cocokkan keyword secara case-insensitive (dengan atau tanpa tanda seru)
                                    if (text.equalsIgnoreCase(keyword) || text.equalsIgnoreCase(keyword.replace("!", ""))) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ Gagal cek keyword Telegram: " + e.getMessage());
        }
        return false;
    }
}
