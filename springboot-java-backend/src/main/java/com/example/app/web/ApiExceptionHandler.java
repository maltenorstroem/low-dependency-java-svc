package com.example.app.web;

import com.example.app.domain.CapacityExceededException;
import com.example.app.domain.ConflictException;
import com.example.app.domain.DomainException;
import com.example.app.domain.IdempotencyKeyReusedException;
import com.example.app.domain.NotFoundException;
import com.example.app.domain.ValidationException;
import com.example.app.domain.VersionMismatchException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Every error response in one place, as RFC 9457 problem documents.
 *
 * <p>The sibling service maps failures with a {@code switch} over a sealed hierarchy, so forgetting
 * a case does not compile. That guarantee cannot be reproduced with annotation-dispatched handlers,
 * so the catch-all for {@link DomainException} below deliberately keeps an exhaustive switch: a new
 * subclass still fails the build, and the handler methods above it are just the fast path.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** The request attribute {@code RequestContextFilter} publishes the request id under. */
    public static final String REQUEST_ID = "requestId";

    private static final int MAX_REPORTED_VIOLATIONS = 20;
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // ---------------------------------------------------------------- domain failures

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> onDomainFailure(DomainException failure, HttpServletRequest request) {
        ProblemDetail problem = switch (failure) {
            case NotFoundException e ->
                    problem(HttpStatus.NOT_FOUND, null, e.getMessage());
            case ValidationException e -> {
                ProblemDetail detail = problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.VALIDATION,
                        "The request body failed validation");
                detail.setProperty("errors", e.violations().stream()
                        .limit(MAX_REPORTED_VIOLATIONS)
                        .map(v -> new Error(v.pointer(), v.message()))
                        .toList());
                yield detail;
            }
            case VersionMismatchException e ->
                    problem(HttpStatus.PRECONDITION_FAILED, ProblemTypes.VERSION_MISMATCH, e.getMessage());
            case ConflictException e ->
                    problem(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, e.getMessage());
            case IdempotencyKeyReusedException e ->
                    problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.IDEMPOTENCY_KEY_REUSED, e.getMessage());
            case CapacityExceededException e ->
                    problem(HttpStatus.INSUFFICIENT_STORAGE, ProblemTypes.CAPACITY_EXCEEDED, e.getMessage());
        };
        return ResponseEntity.status(problem.getStatus()).body(withRequestId(problem, request));
    }

    /** A violation of the request contract, pointed at by a JSON Pointer (RFC 6901). */
    public record Error(String pointer, String detail) {}

    // ---------------------------------------------------------------- anything unforeseen

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> onUnexpectedFailure(Exception failure, HttpServletRequest request) {
        String requestId = requestId(request);
        // Logged in full, reported as nothing: an internal message can carry a query, a key or a
        // path. The request id is the thread between the two.
        LOG.error("http.unhandled_exception requestId={}", requestId, failure);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, null,
                "Internal error; quote the requestId when reporting");
        return ResponseEntity.internalServerError().body(withRequestId(problem, request));
    }

    // ---------------------------------------------------------------- framework failures

    /**
     * Bean-validation failures on the request body. The validator reports every violation at once
     * and its property path is already the shape of a JSON Pointer.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException failure,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.VALIDATION,
                "The request body failed validation");
        List<Error> errors = new ArrayList<>();
        for (ObjectError error : failure.getBindingResult().getAllErrors()) {
            if (errors.size() >= MAX_REPORTED_VIOLATIONS) {
                break;
            }
            errors.add(new Error(pointer(error), error.getDefaultMessage()));
        }
        problem.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(withRequestId(problem, servletRequest(request)));
    }

    /**
     * A body Jackson could not read: malformed JSON, a duplicate key, an unknown field, a value of
     * the wrong type. Jackson stops at the first of these, so unlike a validation failure this
     * reports one problem per round trip.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException failure,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        HttpServletRequest servlet = servletRequest(request);
        BodyTooLargeException tooLarge = causeOfType(failure, BodyTooLargeException.class);
        if (tooLarge != null) {
            // Only reachable for a body that under-declared its length: a truthful Content-Length
            // is refused by BodyLimitFilter before the handler is ever entered.
            ProblemDetail problem = problem(HttpStatus.CONTENT_TOO_LARGE, null, tooLarge.getMessage());
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .header(HttpHeaders.CONNECTION, "close")
                    .body(withRequestId(problem, servlet));
        }
        if (failure.getCause() instanceof MismatchedInputException mismatch) {
            // The body parsed but did not fit the contract: a field of the wrong type, or one that
            // is not part of it. That is a semantic problem, so 422 like any other violation.
            ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.VALIDATION,
                    "The request body failed validation");
            problem.setProperty("errors", List.of(new Error(pointer(mismatch), detail(mismatch))));
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(withRequestId(problem, servlet));
        }
        String detail = failure.getCause() instanceof JacksonException jackson
                ? "Malformed JSON: " + firstLine(jackson.getOriginalMessage())
                : "Malformed or empty request body";
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, null, detail);
        return ResponseEntity.badRequest().body(withRequestId(problem, servlet));
    }

    /** Everything Spring already classifies — 405, 406, 415, 501 — keeps its status and gains a request id. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        if (body instanceof ProblemDetail problem) {
            withRequestId(problem, servletRequest(request));
            if (problem.getTitle() == null) {
                problem.setTitle(ApiException.reason(status));
            }
        }
        return super.createResponseEntity(body, headers, status, request);
    }


    // ---------------------------------------------------------------- helpers

    private static ProblemDetail problem(HttpStatus status, java.net.URI type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (type != null) {
            problem.setType(type);
        }
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }

    private ProblemDetail withRequestId(ProblemDetail problem, HttpServletRequest request) {
        problem.setProperty(REQUEST_ID, requestId(request));
        return problem;
    }

    private static String requestId(HttpServletRequest request) {
        Object id = request == null ? null : request.getAttribute(REQUEST_ID);
        return id == null ? "-" : id.toString();
    }

    private static <T extends Throwable> T causeOfType(Throwable failure, Class<T> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    private static HttpServletRequest servletRequest(WebRequest request) {
        return request instanceof org.springframework.web.context.request.ServletWebRequest servlet
                ? servlet.getRequest()
                : null;
    }

    /** {@code cube.colour.red} becomes {@code /cube/colour/red}; RFC 6901 escaping applied. */
    private static String pointer(ObjectError error) {
        if (!(error instanceof FieldError field)) {
            return "";
        }
        String path = field.getField();
        StringBuilder pointer = new StringBuilder();
        for (String segment : path.split("\\.")) {
            if (!segment.isEmpty()) {
                pointer.append('/').append(escape(segment));
            }
        }
        return pointer.toString();
    }

    private static String pointer(MismatchedInputException failure) {
        StringBuilder pointer = new StringBuilder();
        for (var reference : failure.getPath()) {
            if (reference.getPropertyName() != null) {
                pointer.append('/').append(escape(reference.getPropertyName()));
            } else if (reference.getIndex() >= 0) {
                pointer.append('/').append(reference.getIndex());
            }
        }
        return pointer.toString();
    }

    private static String detail(MismatchedInputException failure) {
        if (failure instanceof tools.jackson.databind.exc.UnrecognizedPropertyException) {
            return "is not a known field";
        }
        // An empty path means the root value itself was the wrong shape, such as an array.
        return failure.getPath().isEmpty() ? "must be a JSON object" : "is not the expected type";
    }

    private static String escape(String name) {
        String shortened = name.length() > 64 ? name.substring(0, 64) : name;
        return shortened.replace("~", "~0").replace("/", "~1");
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "could not be parsed";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() > 200 ? line.substring(0, 200) : line;
    }
}
