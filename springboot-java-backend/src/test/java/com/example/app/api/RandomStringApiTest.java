package com.example.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.app.testing.HttpTestSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** The stream over real HTTP: what arrives on the wire, and every way a request is refused. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RandomStringApiTest extends HttpTestSupport {

    @Test
    void streamsTheRequestedNumberOfEventsAndEnds() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/random-strings?count=3&intervalMs=50", null,
                "Accept", "text/event-stream");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(header(response, "Content-Type").startsWith("text/event-stream"),
                header(response, "Content-Type"));

        List<String> ids = field(response.body(), "id");
        List<String> events = field(response.body(), "event");
        List<String> data = field(response.body(), "data");
        assertEquals(List.of("0", "1", "2"), ids);
        assertEquals(List.of("random-string", "random-string", "random-string"), events);
        assertEquals(3, data.size());
        for (String value : data) {
            assertTrue(value.matches("[A-Za-z0-9]{16}"), value);
        }
    }

    @Test
    void refusesAnIntervalOutsideTheConfiguredBounds() throws Exception {
        for (String interval : new String[] {"49", "60001", "fast", "", "-1"}) {
            HttpResponse<String> response = send("GET", "/v1/random-strings?count=1&intervalMs=" + interval, null);
            assertEquals("intervalMs must be an integer between 50 and 60000",
                    assertProblem(response, 400).get("detail"), interval);
        }
    }

    @Test
    void refusesACountOutsideItsRange() throws Exception {
        for (String count : new String[] {"0", "100001", "many"}) {
            HttpResponse<String> response = send("GET", "/v1/random-strings?count=" + count, null);
            assertEquals(400, response.statusCode(), count);
        }
    }

    @Test
    void refusesUnknownAndRepeatedParameters() throws Exception {
        assertProblem(send("GET", "/v1/random-strings?interval=50", null), 400);
        assertProblem(send("GET", "/v1/random-strings?count=1&count=2", null), 400);
    }

    @Test
    void refusesCallersThatCannotReadAnEventStream() throws Exception {
        assertEquals(406, send("GET", "/v1/random-strings?count=1", null,
                "Accept", "application/json").statusCode());
    }

    /** The values of one SSE field, in order. Spring writes {@code name:value} with no space. */
    private static List<String> field(String body, String name) {
        List<String> values = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (line.startsWith(name + ":")) {
                values.add(line.substring(name.length() + 1).strip());
            }
        }
        return values;
    }
}
