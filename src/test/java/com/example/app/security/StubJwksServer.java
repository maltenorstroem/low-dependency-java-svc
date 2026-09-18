package com.example.app.security;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A key server the tests control: it counts requests, and its document and status can be changed
 * mid-test to stand in for a rotation or an outage.
 */
final class StubJwksServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> document = new AtomicReference<>("{\"keys\":[]}");
    private final AtomicInteger status = new AtomicInteger(200);

    StubJwksServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            requests.incrementAndGet();
            byte[] body = document.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
    }

    void serve(String jwksDocument) {
        document.set(jwksDocument);
    }

    void failWith(int httpStatus) {
        status.set(httpStatus);
    }

    int requestCount() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
