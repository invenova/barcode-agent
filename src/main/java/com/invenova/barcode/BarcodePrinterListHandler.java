package com.invenova.barcode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public class BarcodePrinterListHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (HttpUtils.handleCorsAndMethod(exchange, "GET")) return;

        try {
            List<String> printers = listPrinters();
            JSONArray arr = new JSONArray();
            for (String name : printers) arr.put(new JSONObject().put("name", name));
            HttpUtils.sendResponse(exchange, 200, new JSONObject().put("printers", arr).toString());
        } catch (Exception e) {
            HttpUtils.sendResponse(exchange, 500, new JSONObject().put("error", e.getMessage()).toString());
        }
    }

    /**
     * Lists installed Windows printer queue names.
     * Tries wmic first (faster), falls back to PowerShell (required on Windows 11 22H2+).
     */
    static List<String> listPrinters() {
        try {
            List<String> names = runWmic();
            if (!names.isEmpty()) return names;
        } catch (Exception ignored) {}
        try {
            return runPowerShell();
        } catch (Exception ignored) {}
        return List.of();
    }

    private static List<String> runWmic() throws IOException, InterruptedException {
        return runCommand(
                new String[]{"cmd", "/c", "wmic printer get name /format:list"},
                line -> line.startsWith("Name=") && line.length() > 5,
                line -> line.substring(5).trim());
    }

    private static List<String> runPowerShell() throws IOException, InterruptedException {
        return runCommand(
                new String[]{"powershell", "-NoProfile", "-Command",
                        "Get-Printer | Select-Object -ExpandProperty Name"},
                line -> !line.isEmpty(),
                line -> line);
    }

    private static List<String> runCommand(String[] cmd, Predicate<String> filter, Function<String, String> mapper)
            throws IOException, InterruptedException {
        List<String> names = new ArrayList<>();
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (filter.test(line)) names.add(mapper.apply(line));
            }
        }
        p.waitFor();
        return names;
    }
}
