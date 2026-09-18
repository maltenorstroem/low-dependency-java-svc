package com.example.app.api;

import static com.example.app.testing.Assert.assertEquals;

import com.example.app.http.Access;
import com.example.app.http.Dispatcher;
import com.example.app.http.Response;
import com.example.app.http.Router;
import com.example.app.observability.Metrics;
import com.example.app.security.Authenticator;
import com.example.app.testing.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LoadSheddingTest {

    @Test
    void shedsLoadBeyondConcurrencyLimitAndSurvivesHandlerFailures() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Router router = new Router()
                .get("/slow", Access.PUBLIC, request -> {
                    entered.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return Response.json(200, Map.of("ok", true));
                })
                .get("/boom", Access.PUBLIC, request -> {
                    throw new IllegalStateException("secret internal detail");
                });

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new Dispatcher(router, new Metrics(Clock.systemUTC()), Authenticator.disabled(), 1024, 1));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();

            CompletableFuture<HttpResponse<String>> slow = client.sendAsync(
                    HttpRequest.newBuilder(URI.create(base + "/slow")).build(), BodyHandlers.ofString());
            entered.await(5, TimeUnit.SECONDS);

            HttpResponse<String> shed = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/slow")).build(), BodyHandlers.ofString());
            assertEquals(503, shed.statusCode());
            assertEquals("1", shed.headers().firstValue("Retry-After").orElse(null));

            release.countDown();
            assertEquals(200, slow.get(5, TimeUnit.SECONDS).statusCode());

            HttpResponse<String> boom = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/boom")).build(), BodyHandlers.ofString());
            assertEquals(500, boom.statusCode());
            assertEquals(false, boom.body().contains("secret"));

            // The permit was released: the server still serves.
            release.countDown();
            assertEquals(200, client.send(
                    HttpRequest.newBuilder(URI.create(base + "/slow")).build(), BodyHandlers.ofString()).statusCode());
        } finally {
            server.stop(0);
        }
    }
}
