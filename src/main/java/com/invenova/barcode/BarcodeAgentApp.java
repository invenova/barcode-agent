package com.invenova.barcode;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.List;

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

        List<NativeTray.MenuItem> items = List.of(
                NativeTray.MenuItem.disabled("Version: " + autoUpdater.getCurrentVersion()),
                NativeTray.MenuItem.separator(),
                NativeTray.MenuItem.item("Exit", () -> {
                    autoUpdater.shutdown();
                    server.stop();
                    System.exit(0);
                })
        );

        NativeTray tray = new NativeTray("Invenova Barcode Agent", iconPath, items);

        autoUpdater.setBeforeExit(() -> {
            autoUpdater.shutdown();
            server.stop();
            tray.shutdown();
        });
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
