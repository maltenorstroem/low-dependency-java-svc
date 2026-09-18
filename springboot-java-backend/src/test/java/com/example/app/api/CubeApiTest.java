package com.example.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Black-box tests for the nested aggregate, its contract defaults and its document representation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CubeApiTest extends HttpTestSupport {

    @Test
    void fullLifecycleWithOptimisticConcurrency() throws Exception {
        String body = "{\"description\":\"demo\",\"cube\":{\"length\":10,\"material\":\"MATERIALS_GLASS\"}}";
        HttpResponse<String> created = send("POST", "/v1/cubes", body);
        assertEquals(201, created.statusCode());
        Map<?, ?> cube = object(created);
        String id = (String) cube.get("id");
        assertEquals("\"1\"", header(created, "ETag"));
        assertEquals("/v1/cubes/" + id, header(created, "Location"));
        assertEquals("Glass cube 10 x 100 x 100", cube.get("displayName"));

        Map<?, ?> definition = (Map<?, ?>) cube.get("cube");
        assertEquals("MATERIALS_GLASS", definition.get("material"));
        assertEquals(10, ((Number) definition.get("length")).intValue());
        assertEquals(100, ((Number) definition.get("breadth")).intValue());

        assertEquals(304, send("GET", "/v1/cubes/" + id, null, "If-None-Match", "\"1\"").statusCode());
        assertEquals(428, send("PUT", "/v1/cubes/" + id, body).statusCode());

        String update = "{\"description\":\"changed\",\"cube\":{\"material\":\"MATERIALS_GLASS\"}}";
        HttpResponse<String> updated = send("PUT", "/v1/cubes/" + id, update, "If-Match", "\"1\"");
        assertEquals(200, updated.statusCode());
        assertEquals("\"2\"", header(updated, "ETag"));
        assertEquals("changed", object(updated).get("description"));

        assertEquals(204, send("DELETE", "/v1/cubes/" + id, null, "If-Match", "\"2\"").statusCode());
        assertEquals(404, send("GET", "/v1/cubes/" + id, null).statusCode());
    }

    @Test
    void appliesContractDefaultsToAnEmptyBody() throws Exception {
        HttpResponse<String> created = send("POST", "/v1/cubes", "{}");
        assertEquals(201, created.statusCode());
        Map<?, ?> cube = object(created);
        assertEquals("", cube.get("description"));
        assertEquals("Cube 100 x 100 x 100", cube.get("displayName"));

        Map<?, ?> definition = (Map<?, ?>) cube.get("cube");
        assertEquals("MATERIALS_UNSPECIFIED", definition.get("material"));
        assertEquals(100, ((Number) definition.get("length")).intValue());

        Map<?, ?> colour = (Map<?, ?>) definition.get("colour");
        assertEquals(255, ((Number) colour.get("red")).intValue());
        assertEquals(1, ((Number) colour.get("alpha")).intValue());
    }

    @Test
    void reportsEveryRangeViolationAtOnceWithJsonPointers() throws Exception {
        String body = "{\"cube\":{\"length\":0,\"height\":2000000,"
                + "\"colour\":{\"red\":300,\"alpha\":5}}}";
        Map<?, ?> problem = assertProblem(send("POST", "/v1/cubes", body), 422);
        assertEquals("https://example.com/problems/validation-error", problem.get("type"));

        List<String> pointers = new ArrayList<>();
        for (Object error : (List<?>) problem.get("errors")) {
            pointers.add((String) ((Map<?, ?>) error).get("pointer"));
        }
        // Bean validation walks the whole tree before reporting, so one round trip names them all.
        assertTrue(pointers.contains("/cube/length"), "bad length: " + pointers);
        assertTrue(pointers.contains("/cube/height"), "bad height: " + pointers);
        assertTrue(pointers.contains("/cube/colour/red"), "channel out of range: " + pointers);
        assertTrue(pointers.contains("/cube/colour/alpha"), "alpha out of range: " + pointers);
    }

    @Test
    void reportsNestedShapeErrorsWithJsonPointers() throws Exception {
        // Binding stops at the first structural problem, so these are one per round trip. The
        // pointer still locates it exactly, which is what the contract promises.
        assertEquals("/cube/depth", firstPointer("{\"cube\":{\"depth\":1}}"));
        assertEquals("/cube/material", firstPointer("{\"cube\":{\"material\":\"GLASS\"}}"));
        assertEquals("/cube/colour/green", firstPointer("{\"cube\":{\"colour\":{\"green\":1.5}}}"));
        assertEquals("/description", firstPointer("{\"description\":[]}"));
    }

    @Test
    void servesAGeneratedPdfDocument() throws Exception {
        HttpResponse<String> created = send("POST", "/v1/cubes",
                "{\"description\":\"data (sheet)\",\"cube\":{\"material\":\"MATERIALS_WOOD\"}}");
        assertEquals(201, created.statusCode());
        String id = (String) object(created).get("id");

        HttpResponse<byte[]> pdf = sendForBytes("GET", "/v1/cubes/" + id + "/pdf");
        assertEquals(200, pdf.statusCode());
        assertEquals("application/pdf", header(pdf, "Content-Type"));
        assertEquals("\"1\"", header(pdf, "ETag"));
        assertTrue(header(pdf, "Content-Disposition").contains("cube-" + id + ".pdf"),
                header(pdf, "Content-Disposition"));

        String text = new String(pdf.body(), StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF-1.7"), "PDF header");
        assertTrue(text.contains("%%EOF"), "PDF trailer");

        assertEquals(304, sendForBytes("GET", "/v1/cubes/" + id + "/pdf", "If-None-Match", "\"1\"").statusCode());
        assertEquals(406, sendForBytes("GET", "/v1/cubes/" + id + "/pdf", "Accept", "application/json").statusCode());
    }

    @Test
    void idempotencyKeyMakesPostRetriable() throws Exception {
        String body = "{\"description\":\"boxed\"}";
        HttpResponse<String> first = send("POST", "/v1/cubes", body, "Idempotency-Key", "cube-1");
        HttpResponse<String> retry = send("POST", "/v1/cubes", body, "Idempotency-Key", "cube-1");
        assertEquals(201, first.statusCode());
        assertEquals(object(first).get("id"), object(retry).get("id"));
        assertProblem(send("POST", "/v1/cubes", "{\"description\":\"other\"}", "Idempotency-Key", "cube-1"), 422);
    }

    @Test
    void listPaginatesWithOpaqueCursorsAndLinkHeader() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(201, send("POST", "/v1/cubes", "{\"description\":\"c" + i + "\"}").statusCode());
        }
        HttpResponse<String> page = send("GET", "/v1/cubes?limit=2", null);
        assertEquals(200, page.statusCode());
        assertEquals(2, ((List<?>) object(page).get("items")).size());
        assertTrue(header(page, "Link").contains("rel=\"next\""));
        assertProblem(send("GET", "/v1/cubes?sort=size", null), 400);
    }

    private String firstPointer(String body) throws Exception {
        Map<?, ?> problem = assertProblem(send("POST", "/v1/cubes", body), 422);
        List<?> errors = (List<?>) problem.get("errors");
        return (String) ((Map<?, ?>) errors.get(0)).get("pointer");
    }
}
