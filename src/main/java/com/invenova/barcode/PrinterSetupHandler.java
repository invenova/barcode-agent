package com.invenova.barcode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * POST /setup-printer
 *
 * Extracts setup-printer.ps1 from the JAR and runs it via PowerShell.
 * Installs "Generic / Text Only" for any connected USB printer that has
 * no existing Windows print queue, skipping ports already occupied
 * (e.g. a POS thermal printer with its own driver).
 *
 * Response fields:
 *   success  – true if at least one printer was installed with no errors
 *   noDevice – true if no USB printer was detected (nothing to install)
 */
public class PrinterSetupHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (HttpUtils.handleCorsAndMethod(exchange, "POST")) return;

        try {
            String output = runSetupScript();
            boolean hasError    = output.contains("ERROR:");
            boolean hasInstall  = output.contains("INSTALLED:");
            boolean noDevice    = !hasError && !hasInstall;
            RemoteLogger.info("setup-printer", output);
            HttpUtils.sendResponse(exchange, 200,
                    new JSONObject()
                            .put("success",  hasInstall && !hasError)
                            .put("noDevice", noDevice)
                            .toString());
        } catch (Exception e) {
            RemoteLogger.error("setup-printer", "Script execution failed: " + e.getMessage());
            HttpUtils.sendResponse(exchange, 500,
                    new JSONObject().put("success", false).put("noDevice", false).toString());
        }
    }

    private static String runSetupScript() throws Exception {
        // Extract the bundled script to a temp file
        Path tempScript = Files.createTempFile("setup-printer-", ".ps1");
        try {
            try (InputStream in = PrinterSetupHandler.class.getResourceAsStream("/setup-printer.ps1")) {
                if (in == null) throw new Exception("setup-printer.ps1 not found in JAR");
                Files.write(tempScript, in.readAllBytes());
            }

            Process proc = new ProcessBuilder(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", tempScript.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            proc.waitFor();
            return output;
        } finally {
            Files.deleteIfExists(tempScript);
        }
    }
}
