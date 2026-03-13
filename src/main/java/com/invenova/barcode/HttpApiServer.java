package com.invenova.barcode;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;

public class HttpApiServer {

    private final HttpServer server;

    public HttpApiServer(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            server.createContext("/barcode-print", new BarcodePrintHandler());
            server.createContext("/barcode-printers", new BarcodePrinterListHandler());
            server.createContext("/setup-printer", new PrinterSetupHandler());
            server.setExecutor(null);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create HTTP server", e);
        }
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }
}
