package com.example.app.api;

import com.example.app.domain.Cube;
import com.example.app.domain.CubeInput;
import com.example.app.domain.CubeService;
import com.example.app.domain.Page;
import com.example.app.http.EntityTags;
import com.example.app.http.HttpException;
import com.example.app.http.Request;
import com.example.app.http.Response;
import com.example.app.http.Router;
import com.example.app.security.ScopeNames;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;

/**
 * HTTP adapter for cubes. The entity comes from the {@code lgt.polaris.cube} contract; the
 * collection semantics (pagination, ETags, idempotent POST) follow {@link TaskApi}. Versioned in
 * the path ({@code /v1}); see {@code openapi.yaml} for the contract.
 */
public final class CubeApi {

    static final String COLLECTION = "/v1/cubes";
    private static final String PDF = "application/pdf";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[!#-\\[\\]-~]{1,255}");

    private final CubeService service;
    private final ScopeNames scopes;

    public CubeApi(CubeService service, ScopeNames scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    public void register(Router router) {
        router.get(COLLECTION, scopes.cubesRead(), this::list)
                .post(COLLECTION, scopes.cubesWrite(), this::create)
                .get(COLLECTION + "/{id}", scopes.cubesRead(), this::get)
                .put(COLLECTION + "/{id}", scopes.cubesWrite(), this::replace)
                .delete(COLLECTION + "/{id}", scopes.cubesWrite(), this::delete)
                .get(COLLECTION + "/{id}/pdf", scopes.cubesRead(), this::document);
    }

    private Response list(Request request) {
        request.requireAcceptsJson();
        Map<String, String> query = request.query();
        request.requireQueryParams(query, List.of("limit", "cursor"));
        int limit = parseLimit(query.get("limit"));
        Optional<UUID> after = Optional.ofNullable(query.get("cursor")).map(CubeApi::decodeCursor);

        Page<Cube> page = service.list(after, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.items().stream().map(CubeJson::toJson).toList());
        body.put("nextCursor", page.continueAfter().map(CubeApi::encodeCursor).orElse(null));
        Response response = Response.json(200, body);
        if (page.continueAfter().isPresent()) {
            String next = COLLECTION + "?limit=" + limit + "&cursor=" + encodeCursor(page.continueAfter().get());
            response = response.withHeader("Link", "<" + next + ">; rel=\"next\""); // RFC 8288
        }
        return response;
    }

    private Response create(Request request) {
        request.requireAcceptsJson();
        Optional<String> key = idempotencyKey(request);
        CubeInput input = CubeJson.toInput(request.jsonBody());
        Cube cube = service.create(input, key);
        return Response.json(201, CubeJson.toJson(cube))
                .withHeader("Location", COLLECTION + "/" + cube.id())
                .withHeader("ETag", EntityTags.of(cube.version()));
    }

    private Response get(Request request) {
        request.requireAcceptsJson();
        Cube cube = service.get(id(request));
        String etag = EntityTags.of(cube.version());
        if (EntityTags.ifNoneMatchHits(request, cube.version())) {
            return Response.empty(304).withHeader("ETag", etag);
        }
        return Response.json(200, CubeJson.toJson(cube)).withHeader("ETag", etag);
    }

    private Response replace(Request request) {
        request.requireAcceptsJson();
        UUID id = id(request);
        LongPredicate condition = EntityTags.requireIfMatch(request);
        CubeInput input = CubeJson.toInput(request.jsonBody());
        Cube cube = service.replace(id, input, condition);
        return Response.json(200, CubeJson.toJson(cube)).withHeader("ETag", EntityTags.of(cube.version()));
    }

    private Response delete(Request request) {
        UUID id = id(request);
        LongPredicate condition = EntityTags.requireIfMatch(request);
        service.delete(id, condition);
        return Response.empty(204);
    }

    /** The contract's {@code GetDocument}: a data sheet for the cube, rendered as a PDF. */
    private Response document(Request request) {
        request.requireAccepts(PDF);
        Cube cube = service.get(id(request));
        String etag = EntityTags.of(cube.version());
        if (EntityTags.ifNoneMatchHits(request, cube.version())) {
            return Response.empty(304).withHeader("ETag", etag);
        }
        return Response.bytes(200, PDF, CubePdf.render(cube))
                .withHeader("ETag", etag)
                .withHeader("Content-Disposition", "inline; filename=\"cube-" + cube.id() + ".pdf\"");
    }

    // ---------------------------------------------------------------- parsing helpers

    /** Strict: {@link UUID#fromString} alone accepts non-canonical input such as "1-1-1-1-1". */
    private static UUID id(Request request) {
        String raw = request.pathParam("id");
        if (!UUID_TEXT.matcher(raw).matches()) {
            throw new HttpException(404, "Cube not found");
        }
        return UUID.fromString(raw.toLowerCase(Locale.ROOT));
    }

    private static int parseLimit(String raw) {
        if (raw == null) {
            return DEFAULT_PAGE_SIZE;
        }
        try {
            int limit = Integer.parseInt(raw);
            if (limit >= 1 && limit <= CubeService.MAX_PAGE_SIZE) {
                return limit;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new HttpException(400, "limit must be an integer between 1 and " + CubeService.MAX_PAGE_SIZE);
    }

    /** Cursors are opaque to clients (base64url), so the paging scheme can change without breaking them. */
    static String encodeCursor(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    static UUID decodeCursor(String cursor) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            if (bytes.length == 16) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                return new UUID(buffer.getLong(), buffer.getLong());
            }
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        throw new HttpException(400, "Invalid cursor");
    }

    /** IETF Idempotency-Key header; accepts the Structured Field string form ("...") and a bare token. */
    private static Optional<String> idempotencyKey(Request request) {
        Optional<String> header = request.header("Idempotency-Key");
        if (header.isEmpty()) {
            return Optional.empty();
        }
        String key = header.get().strip();
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            key = key.substring(1, key.length() - 1);
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new HttpException(400, "Idempotency-Key must be 1-255 printable ASCII characters");
        }
        return Optional.of(key);
    }
}
