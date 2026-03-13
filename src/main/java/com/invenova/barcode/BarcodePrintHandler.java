package com.invenova.barcode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class BarcodePrintHandler implements HttpHandler {

    private interface Winspool extends StdCallLibrary {
        boolean OpenPrinter(String name, PointerByReference handle, Pointer defaults);
        int StartDocPrinter(Pointer handle, int level, DocInfo1 info);
        boolean WritePrinter(Pointer handle, Pointer buf, int len, IntByReference written);
        boolean EndDocPrinter(Pointer handle);
        boolean ClosePrinter(Pointer handle);

        @Structure.FieldOrder({"pDocName", "pOutputFile", "pDatatype"})
        class DocInfo1 extends Structure {
            public String pDocName;
            public String pOutputFile;
            public String pDatatype;
        }
    }

    private static final Winspool WINSPOOL =
            Native.load("winspool.drv", Winspool.class, W32APIOptions.ASCII_OPTIONS);

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (HttpUtils.handleCorsAndMethod(exchange, "POST")) return;

        try {
            InputStream is = exchange.getRequestBody();
            JSONObject json = new JSONObject(new String(is.readAllBytes(), StandardCharsets.UTF_8));

            String printerName = json.optString("printerName", "").trim();
            if (printerName.isEmpty()) {
                HttpUtils.sendResponse(exchange, 400, new JSONObject().put("error", "printerName is required.").toString());
                return;
            }

            String language = json.optString("language", "tspl").toLowerCase();
            if (!"tspl".equals(language) && !"zpl".equals(language)) {
                HttpUtils.sendResponse(exchange, 400, new JSONObject().put("error", "Unsupported language: " + language).toString());
                return;
            }

            if (!printerExists(printerName)) {
                RemoteLogger.error("barcode-print", "Printer not found: " + printerName);
                HttpUtils.sendResponse(exchange, 404, new JSONObject().put("error", "Printer not found: " + printerName).toString());
                return;
            }

            byte[] data = json.getString("content").getBytes(StandardCharsets.UTF_8);
            printRaw(printerName, data);
            RemoteLogger.info("barcode-print", "Printed " + data.length + " bytes to [" + printerName + "] lang=" + language);
            HttpUtils.sendResponse(exchange, 200, new JSONObject().put("success", true).toString());

        } catch (JSONException e) {
            RemoteLogger.error("barcode-print", "Invalid JSON: " + e.getMessage());
            HttpUtils.sendResponse(exchange, 400, new JSONObject().put("error", "Invalid JSON: " + e.getMessage()).toString());
        } catch (Exception e) {
            RemoteLogger.error("barcode-print", e.getMessage());
            HttpUtils.sendResponse(exchange, 500, new JSONObject().put("error", e.getMessage()).toString());
        }
    }

    private static boolean printerExists(String name) {
        PointerByReference h = new PointerByReference();
        if (!WINSPOOL.OpenPrinter(name, h, null)) return false;
        WINSPOOL.ClosePrinter(h.getValue());
        return true;
    }

    private static void printRaw(String printerName, byte[] data) throws Exception {
        PointerByReference phPrinter = new PointerByReference();
        if (!WINSPOOL.OpenPrinter(printerName, phPrinter, null))
            throw new Exception("Cannot open printer: " + printerName);

        Pointer hPrinter = phPrinter.getValue();
        try {
            Winspool.DocInfo1 doc = new Winspool.DocInfo1();
            doc.pDocName = "Barcode";
            doc.pDatatype = "RAW";

            if (WINSPOOL.StartDocPrinter(hPrinter, 1, doc) == 0)
                throw new Exception("StartDocPrinter failed for: " + printerName);

            try {
                Memory mem = new Memory(data.length);
                mem.write(0, data, 0, data.length);
                IntByReference written = new IntByReference(0);
                if (!WINSPOOL.WritePrinter(hPrinter, mem, data.length, written))
                    throw new Exception("WritePrinter failed (wrote " + written.getValue() + "/" + data.length + " bytes)");
            } finally {
                WINSPOOL.EndDocPrinter(hPrinter);
            }
        } finally {
            WINSPOOL.ClosePrinter(hPrinter);
        }
    }
}
