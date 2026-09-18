package com.example.app.web;

import java.net.URI;

/**
 * Problem type URIs (RFC 9457). Replace {@link #BASE} with a URL you control that documents each
 * type; the values are part of the published contract, so they match the sibling service exactly.
 */
public final class ProblemTypes {

    public static final String BASE = "https://example.com/problems/";

    public static final URI VALIDATION = URI.create(BASE + "validation-error");
    public static final URI VERSION_MISMATCH = URI.create(BASE + "version-mismatch");
    public static final URI PRECONDITION_REQUIRED = URI.create(BASE + "precondition-required");
    public static final URI IDEMPOTENCY_KEY_REUSED = URI.create(BASE + "idempotency-key-reused");
    public static final URI CONFLICT = URI.create(BASE + "conflict");
    public static final URI CAPACITY_EXCEEDED = URI.create(BASE + "capacity-exceeded");
    public static final URI INVALID_TOKEN = URI.create(BASE + "invalid-token");
    public static final URI INSUFFICIENT_SCOPE = URI.create(BASE + "insufficient-scope");

    private ProblemTypes() {}
}
