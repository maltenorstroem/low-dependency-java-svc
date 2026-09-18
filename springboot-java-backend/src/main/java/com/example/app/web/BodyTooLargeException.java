package com.example.app.web;

import java.io.IOException;

/**
 * Thrown while reading a request body that exceeds the configured cap. An {@link IOException} so it
 * can come out of a {@code ServletInputStream}; {@link ApiExceptionHandler} turns it into a 413
 * once the handler unwinds.
 */
public class BodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int limit;

    public BodyTooLargeException(int limit) {
        super("Request body must not exceed " + limit + " bytes");
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
