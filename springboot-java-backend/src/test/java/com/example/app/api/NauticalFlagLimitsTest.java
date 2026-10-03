package com.example.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.app.testing.HttpTestSupport;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The bounds a stream needs that a request does not: a cap on open streams, and completion at
 * shutdown. A context of its own, because a cap of one would make any other stream test flaky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.nautical-flags.max-concurrent-streams=1")
class NauticalFlagLimitsTest extends HttpTestSupport {

    @Autowired
    NauticalFlagStream streams;

    @Autowired
    NauticalFlags flags;

    @Test
    void refusesStreamsBeyondTheCapAndRecovers() throws Exception {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest open = HttpRequest.newBuilder(uri("/v1/nautical-flags?intervalMs=50&text=" + "A".repeat(256))).build();
        HttpResponse<Stream<String>> held = client.send(open, BodyHandlers.ofLines());
        try (Stream<String> lines = held.body()) {
            assertEquals(200, held.statusCode());
            assertTrue(lines.iterator().hasNext(), "the held stream emits");

            HttpResponse<String> refused = send("GET", "/v1/nautical-flags?text=A", null);
            assertProblem(refused, 503);
            assertEquals("1", header(refused, "Retry-After"));
        }
        // Closing the body drops the connection; the server notices on its next write and frees
        // the slot, so allow it a few intervals.
        Instant deadline = Instant.now().plusSeconds(5);
        int status;
        do {
            status = send("GET", "/v1/nautical-flags?text=A&intervalMs=50", null).statusCode();
        } while (status == 503 && Instant.now().isBefore(deadline));
        assertEquals(200, status);
        client.close();
    }

    @Test
    void completesOpenStreamsWhenStopped() {
        var stream = streams.open(Duration.ofMillis(50), flags.translate("A".repeat(256)));
        try {
            streams.stop();
            // Completion, not an error or a hang: blockLast returns normally.
            stream.blockLast(Duration.ofSeconds(5));
        } finally {
            streams.start();
        }
        assertEquals(1, streams.open(Duration.ofMillis(50), flags.translate("A")).count().block(Duration.ofSeconds(5)));
    }
}
