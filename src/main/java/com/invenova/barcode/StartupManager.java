package com.invenova.barcode;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Manages Windows startup registration for the Barcode Print Agent.
 *
 * Writes/removes an entry in HKCU\...\Run so the agent launches
 * automatically when the user logs in. Only works when running as
 * a jpackage-built native exe — silently skips dev/java.exe mode.
 */
public class StartupManager {

    private static final String REG_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "InvenovaBarcodeAgent";

    /** Returns true if the startup registry entry currently exists. */
    public static boolean isStartupEnabled() {
        try {
            Process proc = new ProcessBuilder(
                    "reg", "query", REG_KEY, "/v", VALUE_NAME)
                    .redirectErrorStream(true).start();
            proc.getInputStream().transferTo(OutputStream.nullOutputStream());
            return proc.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Registers the current executable as a Windows startup entry.
     * No-op when running via bare java.exe (dev mode).
     */
    public static void enableStartup() {
        // Quote the path so Windows handles paths with spaces correctly
        // (e.g. C:\Users\John Smith\AppData\Local\BarcodeAgent\BarcodeAgent.exe)
        getExePath().ifPresent(exe ->
                runReg("reg", "add", REG_KEY, "/v", VALUE_NAME,
                        "/t", "REG_SZ", "/d", "\"" + exe + "\"", "/f"));
    }

    /** Removes the Windows startup registry entry. */
    public static void disableStartup() {
        runReg("reg", "delete", REG_KEY, "/v", VALUE_NAME, "/f");
    }

    /**
     * Returns the path to the current executable, but only if it is
     * a native exe (not java.exe / javaw.exe). jpackage-built apps
     * expose the native launcher here; IDE / jar runs do not.
     */
    static Optional<String> getExePath() {
        return ProcessHandle.current().info().command()
                .filter(p -> {
                    String name = Path.of(p).getFileName().toString().toLowerCase();
                    return !name.startsWith("java");
                });
    }

    private static void runReg(String... cmd) {
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor();
        } catch (Exception ignored) {}
    }
}
