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
class NauticalFlagApiTest extends HttpTestSupport {

    private static final String BAD_TEXT = "text must be 1 to 256 characters from A-Z, 0-9 and space";

    @Test
    void streamsOneFlagPerCharacterAndEnds() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/nautical-flags?text=Ab%201&intervalMs=50", null,
                "Accept", "text/event-stream");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(header(response, "Content-Type").startsWith("text/event-stream"),
                header(response, "Content-Type"));

        List<String> ids = field(response.body(), "id");
        List<String> events = field(response.body(), "event");
        List<String> data = field(response.body(), "data");
        assertEquals(List.of("0", "1", "2", "3"), ids);
        assertEquals(List.of("nautical-flag", "nautical-flag", "nautical-flag", "nautical-flag"), events);
        List<NauticalFlag> flags = new ArrayList<>();
        for (String value : data) {
            flags.add(mapper.readValue(value, NauticalFlag.class));
        }
        assertEquals(List.of(
                new NauticalFlag("A", "Alfa", "⬜🟦"),
                new NauticalFlag("b", "Bravo", "🟥"),
                new NauticalFlag(" ", " ", " "),
                new NauticalFlag("1", "Unaone", "⬜🔴⬜")), flags);
        assertTrue(data.getFirst().contains("\"char\":\"A\""), data.getFirst());
    }

    @Test
    void refusesATextOutsideTheAlphabetOrLength() throws Exception {
        String tooLong = "A".repeat(257);
        for (String text : new String[] {"", "Hi!", "%C3%A4", "a%0Ab", tooLong}) {
            HttpResponse<String> response = send("GET", "/v1/nautical-flags?text=" + text, null);
            assertEquals(BAD_TEXT, assertProblem(response, 400).get("detail"), text);
        }
        assertEquals(BAD_TEXT, assertProblem(send("GET", "/v1/nautical-flags", null), 400).get("detail"));
    }

    @Test
    void refusesAnIntervalOutsideTheConfiguredBounds() throws Exception {
        for (String interval : new String[] {"49", "60001", "fast", "", "-1"}) {
            HttpResponse<String> response = send("GET", "/v1/nautical-flags?text=A&intervalMs=" + interval, null);
            assertEquals("intervalMs must be an integer between 50 and 60000",
                    assertProblem(response, 400).get("detail"), interval);
        }
    }

    @Test
    void refusesUnknownAndRepeatedParameters() throws Exception {
        assertProblem(send("GET", "/v1/nautical-flags?text=A&count=1", null), 400);
        assertProblem(send("GET", "/v1/nautical-flags?text=A&text=B", null), 400);
    }

    @Test
    void refusesCallersThatCannotReadAnEventStream() throws Exception {
        assertEquals(406, send("GET", "/v1/nautical-flags?text=A", null,
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
