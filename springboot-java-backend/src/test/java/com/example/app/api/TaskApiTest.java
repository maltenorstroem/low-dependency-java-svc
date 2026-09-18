package com.example.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Black-box tests over real HTTP against a real server on an ephemeral port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskApiTest extends HttpTestSupport {

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
        assertEquals(428, send("PUT", "/v1/tasks/" + id, update).statusCode());
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
    void acceptsBackWhatItHandedOut() throws Exception {
        HttpResponse<String> created = send("POST", "/v1/tasks", "{\"title\":\"round trip\"}");
        String id = (String) object(created).get("id");
        // The server-owned fields are echoed back verbatim: accepted and ignored, not rejected.
        HttpResponse<String> replaced = send("PUT", "/v1/tasks/" + id, created.body(), "If-Match", "\"1\"");
        assertEquals(200, replaced.statusCode());
        assertEquals(2, ((Number) object(replaced).get("version")).intValue());
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
                assertTrue(header(page, "Link").contains("rel=\"next\""));
            }
            path = cursor == null ? null : "/v1/tasks?limit=2&cursor=" + cursor;
            pages++;
        }
        assertTrue(ids.size() >= 5, "all tasks listed");
        assertTrue(pages >= 3, "multiple pages");
    }

    @Test
    void alwaysSendsNextCursorEvenWhenNull() throws Exception {
        HttpResponse<String> page = send("GET", "/v1/tasks?limit=100", null);
        assertTrue(object(page).containsKey("nextCursor"), "nextCursor is present and null on the last page");
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
        Map<?, ?> unknown = assertProblem(send("POST", "/v1/tasks", "{\"title\":\"x\",\"extra\":1}"), 422);
        assertEquals("https://example.com/problems/validation-error", unknown.get("type"));
        assertEquals(List.of(Map.of("pointer", "/extra", "detail", "is not a known field")),
                unknown.get("errors"));

        Map<?, ?> blank = assertProblem(send("POST", "/v1/tasks", "{\"title\":\" \"}"), 422);
        assertEquals(List.of(Map.of("pointer", "/title", "detail", "must not be blank")), blank.get("errors"));

        assertProblem(send("POST", "/v1/tasks", "{\"title\":5,\"completed\":\"yes\"}"), 422);
        assertProblem(send("POST", "/v1/tasks", "[]"), 422);
    }

    @Test
    void rejectsMalformedBodies() throws Exception {
        assertProblem(send("POST", "/v1/tasks", "{\"title\":"), 400);
        assertProblem(send("POST", "/v1/tasks", ""), 400);
        assertProblem(sendBytes("/v1/tasks", new byte[] {'{', '"', 't', (byte) 0xC3, (byte) 0x28, '"', '}'},
                "application/json"), 400);
        assertProblem(sendBytes("/v1/tasks", "{\"title\":\"x\"}".getBytes(), "text/plain"), 415);
    }

    @Test
    void rejectsDuplicateKeys() throws Exception {
        assertProblem(send("POST", "/v1/tasks", "{\"title\":\"a\",\"title\":\"b\"}"), 400);
    }

    @Test
    void negotiatesContent() throws Exception {
        assertProblem(send("GET", "/v1/tasks", null, "Accept", "text/html"), 406);
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
        assertTrue(header(notAllowed, "Allow").contains("POST"), "Allow lists the usable methods");
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
}
