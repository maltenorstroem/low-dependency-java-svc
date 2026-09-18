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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Black-box tests for the cube resource over real HTTP against a real server. */
public class CubeApiTest {

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
        String body = "{\"description\":\"demo\",\"cube\":{\"length\":10,\"material\":\"MATERIALS_GLASS\"}}";
        HttpResponse<String> created = send("POST", CubeApi.COLLECTION, body);
        assertEquals(201, created.statusCode());
        Map<?, ?> cube = object(created);
        String id = (String) cube.get("id");
        assertEquals(CubeApi.COLLECTION + "/" + id, header(created, "Location"));
        assertEquals("\"1\"", header(created, "ETag"));
        assertEquals("Glass cube 10 x 100 x 100", cube.get("displayName"));

        Map<?, ?> definition = (Map<?, ?>) cube.get("cube");
        assertEquals(0, new java.math.BigDecimal("10").compareTo((java.math.BigDecimal) definition.get("length")));
        assertEquals("MATERIALS_GLASS", definition.get("material"));
        assertEquals(255, ((Number) ((Map<?, ?>) definition.get("colour")).get("red")).intValue());

        HttpResponse<String> fetched = send("GET", CubeApi.COLLECTION + "/" + id, null);
        assertEquals(200, fetched.statusCode());
        assertEquals(cube, object(fetched));
        assertEquals(304, send("GET", CubeApi.COLLECTION + "/" + id, null, "If-None-Match", "\"1\"").statusCode());

        // Read-only fields may be sent straight back, as they come from GET.
        String update = Json.write(cube).replace("\"demo\"", "\"changed\"");
        assertEquals(428, send("PUT", CubeApi.COLLECTION + "/" + id, update).statusCode());
        assertEquals(412, send("PUT", CubeApi.COLLECTION + "/" + id, update, "If-Match", "W/\"1\"").statusCode());

        HttpResponse<String> updated = send("PUT", CubeApi.COLLECTION + "/" + id, update, "If-Match", "\"1\"");
        assertEquals(200, updated.statusCode());
        assertEquals("\"2\"", header(updated, "ETag"));
        assertEquals("changed", object(updated).get("description"));

        assertEquals(412, send("DELETE", CubeApi.COLLECTION + "/" + id, null, "If-Match", "\"1\"").statusCode());
        assertEquals(204, send("DELETE", CubeApi.COLLECTION + "/" + id, null, "If-Match", "\"2\"").statusCode());
        assertEquals(404, send("GET", CubeApi.COLLECTION + "/" + id, null).statusCode());
    }

    @Test
    void appliesContractDefaultsToAnEmptyBody() throws Exception {
        HttpResponse<String> created = send("POST", CubeApi.COLLECTION, "{}");
        assertEquals(201, created.statusCode());
        Map<?, ?> cube = object(created);
        assertEquals("", cube.get("description"));
        assertEquals("Cube 100 x 100 x 100", cube.get("displayName"));
        Map<?, ?> definition = (Map<?, ?>) cube.get("cube");
        assertEquals("MATERIALS_UNSPECIFIED", definition.get("material"));
        Map<?, ?> colour = (Map<?, ?>) definition.get("colour");
        assertEquals(255, ((Number) colour.get("blue")).intValue());
        assertEquals(1, ((Number) colour.get("alpha")).intValue());
    }

    @Test
    void reportsNestedValidationErrorsWithJsonPointers() throws Exception {
        String body = "{\"cube\":{\"colour\":{\"red\":999,\"alpha\":3,\"green\":1.5},"
                + "\"length\":0,\"material\":\"GLASS\",\"depth\":1}}";
        Map<?, ?> problem = assertProblem(send("POST", CubeApi.COLLECTION, body), 422);
        List<String> pointers = new ArrayList<>();
        for (Object error : (List<?>) problem.get("errors")) {
            pointers.add((String) ((Map<?, ?>) error).get("pointer"));
        }
        assertTrue(pointers.contains("/cube/depth"), "unknown nested field: " + pointers);
        assertTrue(pointers.contains("/cube/length"), "bad length: " + pointers);
        assertTrue(pointers.contains("/cube/material"), "unknown material: " + pointers);
        assertTrue(pointers.contains("/cube/colour/red"), "channel out of range: " + pointers);
        assertTrue(pointers.contains("/cube/colour/green"), "non-integer channel: " + pointers);
        assertTrue(pointers.contains("/cube/colour/alpha"), "alpha out of range: " + pointers);

        assertProblem(send("POST", CubeApi.COLLECTION, "{\"nope\":1}"), 422);
        assertProblem(send("POST", CubeApi.COLLECTION, "{\"description\":7}"), 422);
        assertProblem(send("POST", CubeApi.COLLECTION, "{\"cube\":[]}"), 422);
        assertProblem(send("POST", CubeApi.COLLECTION, "[]"), 422);
    }

    @Test
    void listPaginatesWithOpaqueCursorsAndLinkHeader() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(201, send("POST", CubeApi.COLLECTION, "{\"description\":\"page " + i + "\"}").statusCode());
        }
        HttpResponse<String> first = send("GET", CubeApi.COLLECTION + "?limit=1", null);
        assertEquals(200, first.statusCode());
        assertEquals(1, ((List<?>) object(first).get("items")).size());
        String cursor = (String) object(first).get("nextCursor");
        assertTrue(cursor != null, "a next cursor");
        assertContains(header(first, "Link"), "rel=\"next\"");

        HttpResponse<String> second = send("GET", CubeApi.COLLECTION + "?limit=1&cursor=" + cursor, null);
        assertEquals(200, second.statusCode());
        assertTrue(!object(first).get("items").equals(object(second).get("items")), "a different page");

        assertProblem(send("GET", CubeApi.COLLECTION + "?limit=0", null), 400);
        assertProblem(send("GET", CubeApi.COLLECTION + "?limit=abc", null), 400);
        assertProblem(send("GET", CubeApi.COLLECTION + "?cursor=not-base64url!", null), 400);
        assertProblem(send("GET", CubeApi.COLLECTION + "?offset=1", null), 400);
    }

    @Test
    void idempotencyKeyMakesPostRetriable() throws Exception {
        String body = "{\"description\":\"once\"}";
        HttpResponse<String> first = send("POST", CubeApi.COLLECTION, body, "Idempotency-Key", "\"cube-key\"");
        HttpResponse<String> again = send("POST", CubeApi.COLLECTION, body, "Idempotency-Key", "cube-key");
        assertEquals(201, again.statusCode());
        assertEquals(object(first).get("id"), object(again).get("id"));

        assertProblem(send("POST", CubeApi.COLLECTION, "{\"description\":\"twice\"}",
                "Idempotency-Key", "cube-key"), 422);
        assertProblem(send("POST", CubeApi.COLLECTION, body, "Idempotency-Key", "bad key"), 400);
    }

    @Test
    void servesAGeneratedPdfDocument() throws Exception {
        HttpResponse<String> created = send("POST", CubeApi.COLLECTION,
                "{\"description\":\"data (sheet)\",\"cube\":{\"material\":\"MATERIALS_WOOD\"}}");
        String id = (String) object(created).get("id");
        String path = CubeApi.COLLECTION + "/" + id + "/pdf";

        HttpRequest request = HttpRequest.newBuilder(uri(path)).header("Accept", "application/pdf").build();
        HttpResponse<byte[]> pdf = CLIENT.send(request, BodyHandlers.ofByteArray());
        assertEquals(200, pdf.statusCode());
        assertEquals("application/pdf", header(pdf, "Content-Type"));
        assertEquals("\"1\"", header(pdf, "ETag"));
        assertContains(header(pdf, "Content-Disposition"), "cube-" + id + ".pdf");

        String text = new String(pdf.body(), StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF-1.7"), "PDF header");
        assertTrue(text.endsWith("%%EOF\n"), "PDF trailer");
        assertContains(text, "startxref");
        assertContains(text, "(Wood cube 100 x 100 x 100)");
        assertContains(text, "\\(sheet\\)"); // the delimiters are escaped, not dropped
        assertContains(text, "(Volume: 1000000)");

        assertEquals(304, send("GET", path, null, "If-None-Match", "\"1\"").statusCode());
        assertProblem(send("GET", path, null, "Accept", "application/json"), 406);
        assertEquals(200, send("GET", path, null, "Accept", "application/*").statusCode());
        assertEquals(404, send("GET", CubeApi.COLLECTION + "/not-a-uuid/pdf", null).statusCode());
    }

    @Test
    void negotiatesContentAndRoutes() throws Exception {
        assertProblem(send("GET", CubeApi.COLLECTION, null, "Accept", "text/plain"), 406);
        assertEquals(200, send("GET", CubeApi.COLLECTION, null, "Accept", "application/json;q=0.9").statusCode());
        assertEquals(404, send("GET", CubeApi.COLLECTION + "/1-1-1-1-1", null).statusCode());

        HttpResponse<String> notAllowed = send("PATCH", CubeApi.COLLECTION, null);
        assertEquals(405, notAllowed.statusCode());
        assertContains(header(notAllowed, "Allow"), "POST");

        HttpResponse<String> options = send("OPTIONS", CubeApi.COLLECTION + "/0198e2a4-0000-7000-8000-000000000000",
                null);
        assertEquals(204, options.statusCode());
        assertContains(header(options, "Allow"), "DELETE");
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
        return problem;
    }
}
