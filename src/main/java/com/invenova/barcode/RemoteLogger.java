package com.invenova.barcode;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Fire-and-forget logger that POSTs structured log entries to the
 * invenova-barcode backend so they appear in the server logs.
 *
 * Calls never block the calling thread — each send runs on a short-lived
 * daemon thread.  Network failures are silently swallowed.
 */
public final class RemoteLogger {

    private static final String ENDPOINT = "https://barcode-app.invenova.lk/api/agent-log";
    private static final int TIMEOUT_MS  = 4000;

    private RemoteLogger() {}

    public static void info(String source, String message) {
        send("INFO", source, message);
    }

    public static void error(String source, String message) {
        send("ERROR", source, message);
    }

    private static void send(String level, String source, String message) {
        String body = new JSONObject()
                .put("level",   level)
                .put("source",  source)
                .put("message", message)
                .toString();

        Thread t = new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                try (var os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {
                // never block the caller on network errors
            }
        }, "remote-log");
        t.setDaemon(true);
        t.start();
    }
}
