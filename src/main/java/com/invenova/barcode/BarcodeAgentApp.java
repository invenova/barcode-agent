package com.invenova.barcode;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class BarcodeAgentApp {

    private static final int PORT = 8083;
    private static AutoUpdater autoUpdater;

    public static void main(String[] args) throws Exception {
        autoUpdater = new AutoUpdater();
        autoUpdater.cleanupAfterUpdate();

        StartupManager.enableStartup();

        HttpApiServer apiServer = new HttpApiServer(PORT);
        apiServer.start();

        NativeTray tray = buildTray(apiServer);
        tray.show();
        autoUpdater.startBackgroundChecks();
    }

    private static NativeTray buildTray(HttpApiServer server) throws Exception {
        String iconPath = extractIcon();

        AtomicReference<NativeTray> trayRef = new AtomicReference<>();
        AtomicBoolean shutdownCalled = new AtomicBoolean(false);

        Runnable shutdown = () -> {
            if (!shutdownCalled.compareAndSet(false, true)) return;
            autoUpdater.shutdown();
            server.stop();
            NativeTray t = trayRef.get();
            if (t != null) t.shutdown();
        };

        List<NativeTray.MenuItem> items = List.of(
                NativeTray.MenuItem.disabled("Version: " + autoUpdater.getCurrentVersion()),
                NativeTray.MenuItem.separator(),
                NativeTray.MenuItem.item("Exit", () -> {
                    shutdown.run();
                    System.exit(0);
                })
        );

        NativeTray tray = new NativeTray("Invenova Barcode Agent", iconPath, items);
        trayRef.set(tray);

        autoUpdater.setBeforeExit(shutdown);
        autoUpdater.setOnStatusMessage(tray::setStatus);

        tray.setStatus("Running on port " + PORT + ".");
        return tray;
    }

    /** Extract icon.ico from the JAR to a temp file so LoadImageW can load it by path. */
    private static String extractIcon() {
        try (InputStream in = BarcodeAgentApp.class.getResourceAsStream("/icon.ico")) {
            if (in == null) return null;
            File tmp = File.createTempFile("barcode-icon-", ".ico");
            tmp.deleteOnExit();
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                in.transferTo(out);
            }
            return tmp.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

}
