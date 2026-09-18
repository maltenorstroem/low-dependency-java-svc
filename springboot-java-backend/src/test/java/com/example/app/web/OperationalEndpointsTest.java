package com.example.app.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.app.testing.HttpTestSupport;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** The operational surface, at the paths the contract publishes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperationalEndpointsTest extends HttpTestSupport {

    @Test
    void answersLivenessAndReadiness() throws Exception {
        HttpResponse<String> live = send("GET", "/health/live", null);
        assertEquals(200, live.statusCode());
        assertEquals("UP", object(live).get("status"));

        HttpResponse<String> ready = send("GET", "/health/ready", null);
        assertEquals(200, ready.statusCode());
        assertEquals("UP", object(ready).get("status"));
    }

    @Test
    void alsoAnswersTheCanonicalActuatorProbes() throws Exception {
        assertEquals(200, send("GET", "/actuator/health/liveness", null).statusCode());
        assertEquals(200, send("GET", "/actuator/health/readiness", null).statusCode());
    }

    @Test
    void servesMetricsInPrometheusFormat() throws Exception {
        assertEquals(201, send("POST", "/v1/tasks", "{\"title\":\"counted\"}").statusCode());

        HttpResponse<String> metrics = send("GET", "/metrics", null);
        assertEquals(200, metrics.statusCode());
        String body = metrics.body();

        // Micrometer's own naming, not the hand-rolled http_server_requests_total: the label is
        // uri rather than route and the timer carries the count.
        assertTrue(body.contains("http_server_requests_seconds"), "request timer");
        assertTrue(body.contains("uri=\"/v1/tasks\""), "routes are templates, not paths");
        assertTrue(body.contains("jvm_memory_used_bytes"), "jvm metrics");
        assertTrue(body.contains("process_start_time_seconds"), "process metrics");
    }

    @Test
    void labelsRoutesByTemplateSoTheyStayBounded() throws Exception {
        HttpResponse<String> created = send("POST", "/v1/tasks", "{\"title\":\"template\"}");
        String id = (String) object(created).get("id");
        assertEquals(200, send("GET", "/v1/tasks/" + id, null).statusCode());

        String body = send("GET", "/metrics", null).body();
        assertTrue(body.contains("uri=\"/v1/tasks/{id}\""), "the identifier must not become a label");
        assertTrue(!body.contains(id), "no identifier may appear in a metric label");
    }

    @Test
    void countsShedRequestsUnderItsOwnName() throws Exception {
        assertTrue(send("GET", "/metrics", null).body().contains("http_server_requests_rejected_total"),
                "load shedding has no Micrometer equivalent, so it is registered by hand");
    }

    @Test
    void servesTheContract() throws Exception {
        HttpResponse<String> spec = send("GET", "/openapi.yaml", null);
        assertEquals(200, spec.statusCode());
        assertTrue(header(spec, "Content-Type").startsWith("application/yaml"),
                header(spec, "Content-Type"));
        assertTrue(spec.body().contains("openapi: 3.1.0"), "the contract itself");
    }
}
