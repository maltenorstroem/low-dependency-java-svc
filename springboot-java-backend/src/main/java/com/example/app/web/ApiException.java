package com.example.app.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * An HTTP-level failure that is not a domain failure: a malformed cursor, a missing precondition,
 * an unusable header. Extends {@link ErrorResponseException} so Spring already knows how to render
 * it; {@link ApiExceptionHandler} only adds the request id.
 */
public class ApiException extends ErrorResponseException {

    private static final long serialVersionUID = 1L;

    public ApiException(HttpStatusCode status, String detail) {
        this(status, null, detail);
    }

    public ApiException(HttpStatusCode status, URI type, String detail) {
        super(status, problem(status, type, detail), null);
    }

    private static ProblemDetail problem(HttpStatusCode status, URI type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (type != null) {
            problem.setType(type);
        }
        problem.setTitle(reason(status));
        return problem;
    }

    /**
     * RFC 9457 says the title is a short, human-readable summary of the type. Using the status
     * phrase keeps it stable and matches the sibling service.
     */
    static String reason(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved != null ? resolved.getReasonPhrase() : "Error";
    }
}
