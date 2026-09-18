package com.example.app.api;

import static com.example.app.testing.Assert.assertContains;
import static com.example.app.testing.Assert.assertEquals;
import static com.example.app.testing.Assert.assertTrue;

import com.example.app.Application;
import com.example.app.config.Config;
import com.example.app.json.Json;
import com.example.app.observability.Log;
import com.example.app.testing.Test;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Black-box tests over real HTTP against a real server on an ephemeral port. */
public class ApiTest {

    private static final Application APP = start();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static Application start() {
        Log.setThreshold(Level.OFF);
        Config config = Config.fromEnvironment(Map.of(
                "APP_HOST", "127.0.0.1",
                "APP_PORT", "0",
                "APP_MAX_BODY_BYTES", "1024",
                "APP_DRAIN_DELAY_SECONDS", "0"));
        Application app = new Application(config);
        try {
            app.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return app;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void fullLifecycleWithOptimisticConcurrency() throws Exception {
        HttpResponse<String> created = send("POST", "/v1/tasks", "{\"title\":\"write docs\"}");
        assertEquals(201, created.statusCode());
        Map<?, ?> task = object(created);
        String id = (String) task.get("id");
        assertEquals("/v1/tasks/" + id, header(created, "Location"));
        assertEquals("\"1\"", header(created, "ETag"));

        HttpResponse<String> fetched = send("GET", "/v1/tasks/" + id, null);
        assertEquals(200, fetched.statusCode());
        assertEquals(task, object(fetched));

        assertEquals(304, send("GET", "/v1/tasks/" + id, null, "If-None-Match", "\"1\"").statusCode());

        String update = "{\"title\":\"write better docs\",\"completed\":true}";
        HttpResponse<String> missing = send("PUT", "/v1/tasks/" + id, update);
        assertEquals(428, missing.statusCode());
        assertEquals(412, send("PUT", "/v1/tasks/" + id, update, "If-Match", "\"7\"").statusCode());
        assertEquals(412, send("PUT", "/v1/tasks/" + id, update, "If-Match", "W/\"1\"").statusCode());

        HttpResponse<String> updated = send("PUT", "/v1/tasks/" + id, update, "If-Match", "\"1\"");
        assertEquals(200, updated.statusCode());
        assertEquals("\"2\"", header(updated, "ETag"));
        assertEquals(Boolean.TRUE, object(updated).get("completed"));

        assertEquals(412, send("DELETE", "/v1/tasks/" + id, null, "If-Match", "\"1\"").statusCode());
        assertEquals(204, send("DELETE", "/v1/tasks/" + id, null, "If-Match", "\"2\"").statusCode());
        assertEquals(404, send("GET", "/v1/tasks/" + id, null).statusCode());
    }

    @Test
    void listPaginatesWithOpaqueCursorsAndLinkHeader() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(201, send("POST", "/v1/tasks", "{\"title\":\"page " + i + "\"}").statusCode());
        }
        Set<Object> ids = new HashSet<>();
        String path = "/v1/tasks?limit=2";
        int pages = 0;
        while (path != null) {
            HttpResponse<String> page = send("GET", path, null);
            assertEquals(200, page.statusCode());
            Map<?, ?> body = object(page);
            for (Object item : (List<?>) body.get("items")) {
                assertTrue(ids.add(((Map<?, ?>) item).get("id")), "no duplicates across pages");
            }
            Object cursor = body.get("nextCursor");
            if (cursor != null) {
                assertContains(header(page, "Link"), "rel=\"next\"");
            }
            path = cursor == null ? null : "/v1/tasks?limit=2&cursor=" + cursor;
            pages++;
        }
        assertTrue(ids.size() >= 5, "all tasks listed");
        assertTrue(pages >= 3, "multiple pages");
    }

    @Test
    void rejectsBadQueryParameters() throws Exception {
        assertProblem(send("GET", "/v1/tasks?limit=0", null), 400);
        assertProblem(send("GET", "/v1/tasks?limit=abc", null), 400);
        assertProblem(send("GET", "/v1/tasks?cursor=***", null), 400);
        assertProblem(send("GET", "/v1/tasks?limit=1&limit=2", null), 400);
        assertProblem(send("GET", "/v1/tasks?sort=title", null), 400);
    }

    @Test
    void reportsValidationErrorsAsProblemDetails() throws Exception {
        HttpResponse<String> response = send("POST", "/v1/tasks", "{\"title\":\" \",\"extra\":1}");
        Map<?, ?> problem = assertProblem(response, 422);
        assertEquals("https://example.com/problems/validation-error", problem.get("type"));
        assertEquals(List.of(Map.of("pointer", "/extra", "detail", "is not a known field")), problem.get("errors"));

        Map<?, ?> blank = assertProblem(send("POST", "/v1/tasks", "{\"title\":\" \"}"), 422);
        assertEquals(List.of(Map.of("pointer", "/title", "detail", "must not be blank")), blank.get("errors"));

        assertProblem(send("POST", "/v1/tasks", "{\"title\":5,\"completed\":\"yes\"}"), 422);
        assertProblem(send("POST", "/v1/tasks", "[]"), 422);
    }

    @Test
    void rejectsMalformedAndOversizedBodies() throws Exception {
        assertProblem(send("POST", "/v1/tasks", "{\"title\":"), 400);
        assertProblem(send("POST", "/v1/tasks", ""), 400);
        assertProblem(sendBytes("/v1/tasks", new byte[] {'"', (byte) 0xC3, (byte) 0x28, '"'}, "application/json"), 400);
        assertProblem(send("POST", "/v1/tasks", "{\"title\":\"" + "x".repeat(2_000) + "\"}"), 413);
        assertProblem(sendBytes("/v1/tasks", "{\"title\":\"x\"}".getBytes(), "text/plain"), 415);
        assertProblem(sendBytes("/v1/tasks", "{\"title\":\"x\"}".getBytes(), "application/json; charset=latin1"), 415);
        assertEquals(201, sendBytes("/v1/tasks", "{\"title\":\"x\"}".getBytes(), "application/json; charset=UTF-8").statusCode());
    }

    @Test
    void negotiatesContent() throws Exception {
        assertProblem(send("GET", "/v1/tasks", null, "Accept", "text/html"), 406);
        assertProblem(send("GET", "/v1/tasks", null, "Accept", "application/json;q=0"), 406);
        assertEquals(200, send("GET", "/v1/tasks", null, "Accept", "text/html, application/*;q=0.5").statusCode());
    }

    @Test
    void distinguishesRoutingFailures() throws Exception {
        assertProblem(send("GET", "/nope", null), 404);
        assertProblem(send("GET", "/v1/tasks/", null), 404);
        assertProblem(send("GET", "/v1/tasks/not-a-uuid", null), 404);
        assertProblem(send("GET", "/v1/tasks/1-1-1-1-1", null), 404);

        HttpResponse<String> notAllowed = send("PATCH", "/v1/tasks", "{}");
        assertProblem(notAllowed, 405);
        assertEquals("GET, HEAD, OPTIONS, POST", header(notAllowed, "Allow"));

        HttpResponse<String> options = send("OPTIONS", "/v1/tasks/00000000-0000-7000-8000-000000000000", null);
        assertEquals(204, options.statusCode());
        assertEquals("DELETE, GET, HEAD, OPTIONS, PUT", header(options, "Allow"));

        assertProblem(send("BREW", "/v1/tasks", null), 501);
    }

    @Test
    void supportsHead() throws Exception {
        HttpResponse<String> head = send("HEAD", "/health/live", null);
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals("15", header(head, "Content-Length"));
    }

    @Test
    void propagatesSafeRequestIdsOnly() throws Exception {
        assertEquals("abc-123", header(send("GET", "/health/live", null, "X-Request-Id", "abc-123"), "X-Request-Id"));
        String replaced = header(send("GET", "/health/live", null, "X-Request-Id", "bad id\"}"), "X-Request-Id");
        assertEquals(36, replaced.length());
        Map<?, ?> problem = assertProblem(send("GET", "/nope", null, "X-Request-Id", "trace-me"), 404);
        assertEquals("trace-me", problem.get("requestId"));
    }

    @Test
    void idempotencyKeyMakesPostRetriable() throws Exception {
        String body = "{\"title\":\"charge card\"}";
        HttpResponse<String> first = send("POST", "/v1/tasks", body, "Idempotency-Key", "\"order-42\"");
        HttpResponse<String> retry = send("POST", "/v1/tasks", body, "Idempotency-Key", "\"order-42\"");
        assertEquals(201, first.statusCode());
        assertEquals(201, retry.statusCode());
        assertEquals(object(first).get("id"), object(retry).get("id"));

        assertProblem(send("POST", "/v1/tasks", "{\"title\":\"other\"}", "Idempotency-Key", "\"order-42\""), 422);
        assertProblem(send("POST", "/v1/tasks", body, "Idempotency-Key", "\"has space\""), 400);
    }

    @Test
    void exposesOperationalEndpointsAndSecurityHeaders() throws Exception {
        HttpResponse<String> ready = send("GET", "/health/ready", null);
        assertEquals(200, ready.statusCode());
        assertEquals("nosniff", header(ready, "X-Content-Type-Options"));
        assertEquals("no-store", header(ready, "Cache-Control"));

        HttpResponse<String> metrics = send("GET", "/metrics", null);
        assertEquals(200, metrics.statusCode());
        assertContains(metrics.body(), "http_server_requests_total{method=\"GET\",route=\"/health/ready\",status=\"200\"}");
        assertContains(metrics.body(), "http_server_request_duration_seconds_bucket");

        HttpResponse<String> spec = send("GET", "/openapi.yaml", null);
        assertEquals(200, spec.statusCode());
        assertContains(spec.body(), "openapi: 3.1.0");
    }

    // ------------------------------------------------------------------ helpers

    private static HttpResponse<String> send(String method, String path, String body, String... headers)
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

    private static HttpResponse<String> sendBytes(String path, byte[] body, String contentType)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", contentType)
                .POST(BodyPublishers.ofByteArray(body))
                .build();
        return CLIENT.send(request, BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + APP.port() + path);
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    private static Map<?, ?> object(HttpResponse<String> response) {
        return (Map<?, ?>) Json.parse(response.body());
    }

    private static Map<?, ?> assertProblem(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode());
        assertEquals("application/problem+json", header(response, "Content-Type"));
        Map<?, ?> problem = object(response);
        assertEquals(status, ((Number) problem.get("status")).intValue());
        assertTrue(problem.get("title") instanceof String, "problem has a title");
        assertTrue(problem.get("requestId") instanceof String, "problem has a requestId");
        return problem;
    }
}
