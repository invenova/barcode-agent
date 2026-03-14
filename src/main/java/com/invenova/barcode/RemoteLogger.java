package com.invenova.barcode;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fire-and-forget logger that POSTs structured log entries to the
 * invenova-barcode backend so they appear in the server logs.
 *
 * Calls never block the calling thread — each send is queued on a single
 * shared daemon thread.  Network failures are silently swallowed.
 */
public final class RemoteLogger {

    private static final String ENDPOINT = "https://barcode-app.invenova.lk/api/agent-log";
    private static final int TIMEOUT_MS  = 4000;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "remote-log");
        t.setDaemon(true);
        return t;
    });

    private RemoteLogger() {}

    /** Blocks until all queued log entries are sent (or 5 s timeout). Call before System.exit(). */
    public static void flush() {
        POOL.shutdown();
        try { POOL.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

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

        POOL.execute(() -> {
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
        });
    }
}
