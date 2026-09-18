package com.example.app.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.Map;
import com.example.app.testing.HttpTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** The cross-cutting filters: request ids, body limits and content encodings. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.max-body-bytes=1024")
class RequestFilterTest extends HttpTestSupport {

    @Test
    void propagatesSafeRequestIdsOnly() throws Exception {
        assertEquals("abc-123",
                header(send("GET", "/v1/tasks", null, "X-Request-Id", "abc-123"), "X-Request-Id"));

        String replaced = header(send("GET", "/v1/tasks", null, "X-Request-Id", "bad id\"}"), "X-Request-Id");
        assertEquals(36, replaced.length(), "a rejected id is replaced by a fresh UUID, not sanitized");
        assertNotEquals("bad id\"}", replaced);
    }

    @Test
    void echoesTheRequestIdIntoTheProblemBody() throws Exception {
        Map<?, ?> problem = assertProblem(send("GET", "/v1/tasks/not-a-uuid", null,
                "X-Request-Id", "trace-me"), 404);
        assertEquals("trace-me", problem.get("requestId"));
    }

    @Test
    void alwaysGivesTheResponseARequestId() throws Exception {
        String generated = header(send("GET", "/v1/tasks", null), "X-Request-Id");
        assertEquals(36, generated.length());
    }

    @Test
    void rejectsOversizedBodies() throws Exception {
        String oversized = "{\"title\":\"" + "x".repeat(2_000) + "\"}";
        HttpResponse<String> response = send("POST", "/v1/tasks", oversized);
        assertProblem(response, 413);
        assertEquals("close", header(response, "Connection"));
    }

    @Test
    void rejectsOversizedChunkedBodiesThatDeclareNoLength() throws Exception {
        // No Content-Length, so the cap can only be enforced while the body is being read.
        HttpResponse<String> response = sendChunked("/v1/tasks",
                "{\"title\":\"" + "y".repeat(4_000) + "\"}");
        assertEquals(413, response.statusCode(), () -> "body was " + response.body());
    }

    @Test
    void acceptsBodiesInsideTheLimit() throws Exception {
        assertEquals(201, send("POST", "/v1/tasks", "{\"title\":\"small\"}").statusCode());
    }

    @Test
    void rejectsContentEncodingsItWillNotDecode() throws Exception {
        HttpResponse<String> response = send("POST", "/v1/tasks", "{\"title\":\"x\"}",
                "Content-Encoding", "gzip");
        assertProblem(response, 415);
        assertEquals("identity", header(response, "Accept-Encoding"));

        assertEquals(201, send("POST", "/v1/tasks", "{\"title\":\"plain\"}",
                "Content-Encoding", "identity").statusCode());
    }

    @Test
    void keepsSecurityHeadersOnEveryResponse() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/tasks", null);
        assertEquals("nosniff", header(response, "X-Content-Type-Options"));
        assertEquals("DENY", header(response, "X-Frame-Options"));
        assertTrue(header(response, "Cache-Control").contains("no-store"),
                header(response, "Cache-Control"));
    }

    private HttpResponse<String> sendChunked(String path, String body) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                        () -> new java.io.ByteArrayInputStream(body.getBytes())))
                .build();
        return java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    }
}
