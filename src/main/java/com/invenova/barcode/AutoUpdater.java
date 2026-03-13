package com.invenova.barcode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class AutoUpdater {

    private static final String GITHUB_REPO = "invenova/barcode-agent";
    private static final String GITHUB_API_URL =
            "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    private static final long CHECK_INTERVAL_HOURS = 6;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final String JVM_OPTIONS = "-Xmx512m";
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private final Path jarPath;
    private final Path appDir;
    private final Path updateDir;
    private final String currentVersion;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private Consumer<String> onUpdateDownloaded;
    private Consumer<String> onStatusMessage;

    public AutoUpdater() {
        this.jarPath = resolveJarPath();
        this.appDir = jarPath != null ? jarPath.getParent() : Path.of(System.getProperty("user.dir"));
        this.updateDir = appDir.resolve("update");
        this.currentVersion = loadCurrentVersion();
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auto-updater");
            t.setDaemon(true);
            return t;
        });
    }

    public void setOnUpdateDownloaded(Consumer<String> callback) {
        this.onUpdateDownloaded = callback;
    }

    public void setOnStatusMessage(Consumer<String> callback) {
        this.onStatusMessage = callback;
    }

    private void notifyStatus(String message) {
        if (onStatusMessage != null) onStatusMessage.accept(message);
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void startBackgroundChecks() {
        scheduler.scheduleAtFixedRate(this::checkAndDownload, 0, CHECK_INTERVAL_HOURS, TimeUnit.HOURS);
    }

    public void checkNow() {
        scheduler.execute(this::checkAndDownload);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    public boolean applyUpdate(Runnable beforeExit) {
        Path stagedJar = findStagedJar();
        if (stagedJar == null) return false;

        if (jarPath == null) {
            RemoteLogger.error("auto-updater", "Cannot determine current JAR path. Skipping update.");
            return false;
        }

        try {
            Path script = writeUpdateScript(jarPath, stagedJar);
            launchUpdateScript(script);
            if (beforeExit != null) beforeExit.run();
            System.exit(0);
            return true;
        } catch (IOException e) {
            RemoteLogger.error("auto-updater", "Failed to launch update script: " + e.getMessage());
            return false;
        }
    }

    public void cleanupAfterUpdate() {
        if (jarPath == null) return;
        deleteQuietly(jarPath.resolveSibling(jarPath.getFileName() + ".backup"));
        deleteQuietly(appDir.resolve(IS_WINDOWS ? "update.bat" : "update.sh"));
        cleanDirectory(updateDir, ".tmp");
    }

    // ---- Update check & download ----

    private void checkAndDownload() {
        try {
            JSONObject release = fetchLatestRelease();
            if (release == null) {
                notifyStatus("Update check failed. Check your connection.");
                return;
            }

            String tagName = release.getString("tag_name");
            String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;

            if (!isNewerVersion(latestVersion, currentVersion)) {
                notifyStatus("Already up to date (v" + currentVersion + ").");
                return;
            }

            RemoteLogger.info("auto-updater", "New version available: " + latestVersion);

            String downloadUrl = findJarAssetUrl(release);
            if (downloadUrl == null) {
                RemoteLogger.error("auto-updater", "No JAR asset found in release " + latestVersion);
                notifyStatus("Update v" + latestVersion + " found but no download available.");
                return;
            }

            notifyStatus("Downloading update v" + latestVersion + "...");

            if (downloadUpdate(downloadUrl, latestVersion) && onUpdateDownloaded != null) {
                onUpdateDownloaded.accept(latestVersion);
            }
        } catch (Exception e) {
            RemoteLogger.error("auto-updater", "Error checking for updates: " + e.getMessage());
            notifyStatus("Update check failed: " + e.getMessage());
        }
    }

    private JSONObject fetchLatestRelease() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API_URL))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "invenova-barcode-print-agent/" + currentVersion)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 404) {
            RemoteLogger.error("auto-updater", "No releases found on GitHub.");
            return null;
        }
        if (status == 403 || status == 429) {
            RemoteLogger.error("auto-updater", "Rate limited by GitHub. Retry-After: "
                    + response.headers().firstValue("Retry-After").orElse("unknown") + "s");
            return null;
        }
        if (status != 200) {
            RemoteLogger.error("auto-updater", "Unexpected GitHub API status: " + status);
            return null;
        }
        return new JSONObject(response.body());
    }

    private String findJarAssetUrl(JSONObject release) {
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (asset.getString("name").endsWith(".jar")) {
                return asset.getString("browser_download_url");
            }
        }
        return null;
    }

    private boolean downloadUpdate(String url, String version) throws IOException, InterruptedException {
        Files.createDirectories(updateDir);
        cleanDirectory(updateDir, ".jar", ".tmp");

        Path targetPath = updateDir.resolve("invenova-barcode-print-agent-" + version + ".jar");
        Path tempPath = updateDir.resolve("download.tmp");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "invenova-barcode-print-agent/" + currentVersion)
                .timeout(DOWNLOAD_TIMEOUT)
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            RemoteLogger.error("auto-updater", "Download failed: HTTP " + response.statusCode());
            return false;
        }

        try (InputStream in = response.body()) {
            long bytes = Files.copy(in, tempPath, StandardCopyOption.REPLACE_EXISTING);
            RemoteLogger.info("auto-updater", "Downloaded " + (bytes / 1024) + " KB for v" + version);
        }

        if (!hasZipMagicBytes(tempPath)) {
            Files.deleteIfExists(tempPath);
            RemoteLogger.error("auto-updater", "Downloaded file for v" + version + " failed JAR integrity check. Discarded.");
            return false;
        }

        Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        RemoteLogger.info("auto-updater", "Update v" + version + " staged successfully.");
        return true;
    }

    private Path writeUpdateScript(Path currentJar, Path stagedJar) throws IOException {
        Path script = appDir.resolve(IS_WINDOWS ? "update.bat" : "update.sh");

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        long pid = ProcessHandle.current().pid();

        if (IS_WINDOWS) {
            String content = String.join("\r\n",
                    "@echo off",
                    "echo Waiting for process " + pid + " to exit...",
                    ":wait_loop",
                    "tasklist /FI \"PID eq " + pid + "\" 2>NUL | find /I \"" + pid + "\" >NUL",
                    "if not errorlevel 1 (",
                    "    timeout /t 1 /nobreak >NUL",
                    "    goto wait_loop",
                    ")",
                    "echo Applying update...",
                    "if exist \"" + currentJar + "\" (",
                    "    move /Y \"" + currentJar + "\" \"" + currentJar + ".backup\"",
                    ")",
                    "move /Y \"" + stagedJar + "\" \"" + currentJar + "\"",
                    "echo Starting updated application...",
                    "start \"\" \"" + javaBin + "\" " + JVM_OPTIONS + " -jar \"" + currentJar + "\"",
                    "exit"
            );
            Files.writeString(script, content);
        } else {
            String content = String.join("\n",
                    "#!/bin/sh",
                    "echo 'Waiting for process " + pid + " to exit...'",
                    "while kill -0 " + pid + " 2>/dev/null; do sleep 1; done",
                    "echo 'Applying update...'",
                    "if [ -f '" + currentJar + "' ]; then",
                    "    mv '" + currentJar + "' '" + currentJar + ".backup'",
                    "fi",
                    "mv '" + stagedJar + "' '" + currentJar + "'",
                    "echo 'Starting updated application...'",
                    "\"" + javaBin + "\" " + JVM_OPTIONS + " -jar '" + currentJar + "' &",
                    "exit 0"
            );
            Files.writeString(script, content);
            script.toFile().setExecutable(true);
        }

        return script;
    }

    private void launchUpdateScript(Path script) throws IOException {
        ProcessBuilder pb = IS_WINDOWS
                ? new ProcessBuilder("cmd.exe", "/c", "start", "/min", "", script.toString())
                : new ProcessBuilder("sh", script.toString());
        pb.directory(appDir.toFile());
        pb.redirectErrorStream(true);
        pb.start();
    }

    // ---- Version comparison ----

    static boolean isNewerVersion(String latest, String current) {
        int[] l = parseVersion(latest);
        int[] c = parseVersion(current);
        for (int i = 0; i < Math.max(l.length, c.length); i++) {
            int lv = i < l.length ? l[i] : 0;
            int cv = i < c.length ? c[i] : 0;
            if (lv != cv) return lv > cv;
        }
        return false;
    }

    private static int[] parseVersion(String version) {
        String[] parts = version.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException e) { result[i] = 0; }
        }
        return result;
    }

    // ---- Helpers ----

    private String loadCurrentVersion() {
        try (InputStream is = getClass().getResourceAsStream("/version.properties")) {
            if (is == null) return "0.0.0";
            Properties props = new Properties();
            props.load(is);
            return props.getProperty("app.version", "0.0.0");
        } catch (IOException e) {
            return "0.0.0";
        }
    }

    private static Path resolveJarPath() {
        try {
            Path p = Path.of(AutoUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (p.toString().endsWith(".jar")) return p;
        } catch (Exception ignored) {}
        return null;
    }

    private Path findStagedJar() {
        try (var stream = Files.list(updateDir)) {
            return stream.filter(p -> p.toString().endsWith(".jar")).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean hasZipMagicBytes(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            byte[] magic = new byte[4];
            return is.read(magic) == 4
                    && magic[0] == 0x50 && magic[1] == 0x4B
                    && magic[2] == 0x03 && magic[3] == 0x04;
        } catch (IOException e) {
            return false;
        }
    }

    private void cleanDirectory(Path dir, String... suffixes) {
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.toString();
                for (String suffix : suffixes) if (name.endsWith(suffix)) return true;
                return false;
            }).forEach(this::deleteQuietly);
        } catch (IOException ignored) {}
    }

    private void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
    }
}
