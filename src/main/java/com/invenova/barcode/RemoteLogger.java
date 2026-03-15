package com.invenova.barcode;

import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fire-and-forget logger that POSTs structured log entries to the
 * invenova-barcode backend so they appear in the server logs.
 *
 * Calls never block the calling thread — each send is queued on a single
 * shared daemon thread.  Network failures are silently swallowed.
 */
public final class RemoteLogger {

    private static final String ENDPOINT = "https://barcode-app.invenova.lk/api/agent-log";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(4))
            .build();

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "remote-log");
        t.setDaemon(true);
        return t;
    });

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

        POOL.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(ENDPOINT))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(java.time.Duration.ofSeconds(4))
                        .build();
                HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // never block the caller on network errors
            }
        });
    }
}
