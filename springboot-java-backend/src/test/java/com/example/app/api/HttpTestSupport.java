package com.example.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

/**
 * Black-box HTTP support: a real client against a real server on an ephemeral port. Deliberately
 * the JDK's own HttpClient rather than TestRestTemplate, so nothing in the test stack can smooth
 * over a header, a status code or a content type the way a Spring-aware client might.
 *
 * <p>Not named {@code *Test}, so it is not collected as a suite of its own.
 */
abstract class HttpTestSupport {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    protected int port;

    @Autowired
    protected ObjectMapper mapper;

    protected HttpResponse<String> send(String method, String path, String body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        builder.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return CLIENT.send(builder.build(), BodyHandlers.ofString());
    }

    protected HttpResponse<String> sendBytes(String path, byte[] body, String contentType)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", contentType)
                .POST(BodyPublishers.ofByteArray(body))
                .build();
        return CLIENT.send(request, BodyHandlers.ofString());
    }

    protected HttpResponse<byte[]> sendForBytes(String method, String path, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        builder.method(method, BodyPublishers.noBody());
        return CLIENT.send(builder.build(), BodyHandlers.ofByteArray());
    }

    protected URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    protected static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    protected Map<?, ?> object(HttpResponse<String> response) {
        return mapper.readValue(response.body(), Map.class);
    }

    protected Map<?, ?> assertProblem(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), () -> "body was " + response.body());
        assertTrue(header(response, "Content-Type").startsWith("application/problem+json"),
                () -> "content type was " + header(response, "Content-Type"));
        Map<?, ?> problem = object(response);
        assertEquals(status, ((Number) problem.get("status")).intValue());
        assertInstanceOf(String.class, problem.get("title"), "problem has a title");
        return problem;
    }
}
