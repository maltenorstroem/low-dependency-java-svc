package com.example.app.api;

import com.example.app.domain.Cube;
import com.example.app.domain.CubeService;
import com.example.app.domain.Page;
import com.example.app.web.AllowedQueryParams;
import com.example.app.web.ApiException;
import com.example.app.web.IdempotencyKeys;
import com.example.app.web.Preconditions;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongPredicate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP adapter for cubes. The entity comes from the {@code lgt.polaris.cube} contract; the
 * collection semantics (pagination, ETags, idempotent POST) follow {@link TaskController}.
 */
@RestController
@RequestMapping(path = CubeController.COLLECTION, produces = MediaType.APPLICATION_JSON_VALUE)
public class CubeController {

    static final String COLLECTION = "/v1/cubes";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String NOT_FOUND = "Cube not found";

    private final CubeService service;

    public CubeController(CubeService service) {
        this.service = service;
    }

    @GetMapping
    @AllowedQueryParams({"limit", "cursor"})
    ResponseEntity<CollectionResponse<CubeResource>> list(
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {

        int size = parseLimit(limit);
        Optional<UUID> after = Optional.ofNullable(cursor).map(Cursors::decode);
        Page<Cube> page = service.list(after, size);

        CollectionResponse<CubeResource> body = new CollectionResponse<>(
                page.items().stream().map(CubeResource::of).toList(),
                page.continueAfter().map(Cursors::encode).orElse(null));

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (page.continueAfter().isPresent()) {
            String next = COLLECTION + "?limit=" + size + "&cursor=" + Cursors.encode(page.continueAfter().get());
            response.header(HttpHeaders.LINK, "<" + next + ">; rel=\"next\""); // RFC 8288
        }
        return response.body(body);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CubeResource> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CubeResource body) {

        Cube cube = service.create(body.toInput(), IdempotencyKeys.parse(idempotencyKey));
        return ResponseEntity.created(URI.create(COLLECTION + "/" + cube.id()))
                .eTag(Preconditions.etag(cube.version()))
                .body(CubeResource.of(cube));
    }

    @GetMapping("/{id}")
    ResponseEntity<CubeResource> get(
            @PathVariable String id,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        Cube cube = service.get(Identifiers.parse(id, NOT_FOUND));
        String etag = Preconditions.etag(cube.version());
        if (Preconditions.ifNoneMatchHits(ifNoneMatch, cube.version())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(CubeResource.of(cube));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CubeResource> replace(
            @PathVariable String id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CubeResource body) {

        UUID identifier = Identifiers.parse(id, NOT_FOUND);
        LongPredicate condition = Preconditions.requireIfMatch(ifMatch);
        Cube cube = service.replace(identifier, body.toInput(), condition);
        return ResponseEntity.ok().eTag(Preconditions.etag(cube.version())).body(CubeResource.of(cube));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {

        UUID identifier = Identifiers.parse(id, NOT_FOUND);
        service.delete(identifier, Preconditions.requireIfMatch(ifMatch));
        return ResponseEntity.noContent().build();
    }

    /** The same entity, rendered as a document. The writer is the one from the sibling service. */
    @GetMapping(path = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> document(
            @PathVariable String id,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        Cube cube = service.get(Identifiers.parse(id, NOT_FOUND));
        String etag = Preconditions.etag(cube.version());
        if (Preconditions.ifNoneMatchHits(ifNoneMatch, cube.version())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("cube-" + cube.id() + ".pdf").build().toString())
                .body(CubePdf.render(cube));
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
        throw new ApiException(HttpStatus.BAD_REQUEST,
                "limit must be an integer between 1 and " + CubeService.MAX_PAGE_SIZE);
    }
}
