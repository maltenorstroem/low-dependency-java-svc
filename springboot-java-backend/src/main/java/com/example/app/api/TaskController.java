package com.example.app.api;

import com.example.app.domain.Page;
import com.example.app.domain.Task;
import com.example.app.domain.TaskService;
import com.example.app.web.AllowedQueryParams;
import com.example.app.web.ApiException;
import com.example.app.web.IdempotencyKeys;
import com.example.app.web.Preconditions;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongPredicate;
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
 * HTTP adapter for tasks. Versioned in the path ({@code /v1}); see {@code openapi.yaml} for the
 * contract. Scopes are enforced by the security filter chain, not here.
 */
@RestController
@RequestMapping(path = TaskController.COLLECTION, produces = MediaType.APPLICATION_JSON_VALUE)
public class TaskController {

    static final String COLLECTION = "/v1/tasks";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String NOT_FOUND = "Task not found";

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @GetMapping
    @AllowedQueryParams({"limit", "cursor"})
    ResponseEntity<CollectionResponse<TaskResource>> list(
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {

        int size = parseLimit(limit);
        Optional<UUID> after = Optional.ofNullable(cursor).map(Cursors::decode);
        Page<Task> page = service.list(after, size);

        CollectionResponse<TaskResource> body = new CollectionResponse<>(
                page.items().stream().map(TaskResource::of).toList(),
                page.continueAfter().map(Cursors::encode).orElse(null));

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (page.continueAfter().isPresent()) {
            String next = COLLECTION + "?limit=" + size + "&cursor=" + Cursors.encode(page.continueAfter().get());
            response.header(HttpHeaders.LINK, "<" + next + ">; rel=\"next\""); // RFC 8288
        }
        return response.body(body);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TaskResource> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TaskResource body) {

        Task task = service.create(body.toInput(), IdempotencyKeys.parse(idempotencyKey));
        return ResponseEntity.created(URI.create(COLLECTION + "/" + task.id()))
                .eTag(Preconditions.etag(task.version()))
                .body(TaskResource.of(task));
    }

    @GetMapping("/{id}")
    ResponseEntity<TaskResource> get(
            @PathVariable String id,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        Task task = service.get(Identifiers.parse(id, NOT_FOUND));
        String etag = Preconditions.etag(task.version());
        if (Preconditions.ifNoneMatchHits(ifNoneMatch, task.version())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(TaskResource.of(task));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TaskResource> replace(
            @PathVariable String id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody TaskResource body) {

        UUID identifier = Identifiers.parse(id, NOT_FOUND);
        LongPredicate condition = Preconditions.requireIfMatch(ifMatch);
        Task task = service.replace(identifier, body.toInput(), condition);
        return ResponseEntity.ok().eTag(Preconditions.etag(task.version())).body(TaskResource.of(task));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {

        UUID identifier = Identifiers.parse(id, NOT_FOUND);
        service.delete(identifier, Preconditions.requireIfMatch(ifMatch));
        return ResponseEntity.noContent().build();
    }

    /**
     * Taken as text so that a non-numeric value is one 400 from here rather than a type-mismatch
     * from the framework, keeping the message identical for every bad limit.
     */
    private static int parseLimit(String raw) {
        if (raw == null) {
            return DEFAULT_PAGE_SIZE;
        }
        try {
            int limit = Integer.parseInt(raw);
            if (limit >= 1 && limit <= TaskService.MAX_PAGE_SIZE) {
                return limit;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new ApiException(HttpStatus.BAD_REQUEST,
                "limit must be an integer between 1 and " + TaskService.MAX_PAGE_SIZE);
    }
}
