package com.example.app.http;

import com.example.app.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error responses as "Problem Details for HTTP APIs" (RFC 9457), the IETF standard error format.
 *
 * <p>Replace {@link #TYPE_BASE} with a URL you control that documents each problem type.
 */
public final class Problems {

    public static final String CONTENT_TYPE = "application/problem+json";
    public static final String BLANK = "about:blank";
    public static final String TYPE_BASE = "https://example.com/problems/";

    public static final String VALIDATION = TYPE_BASE + "validation-error";
    public static final String VERSION_MISMATCH = TYPE_BASE + "version-mismatch";
    public static final String PRECONDITION_REQUIRED = TYPE_BASE + "precondition-required";
    public static final String IDEMPOTENCY_KEY_REUSED = TYPE_BASE + "idempotency-key-reused";
    public static final String CONFLICT = TYPE_BASE + "conflict";
    public static final String CAPACITY_EXCEEDED = TYPE_BASE + "capacity-exceeded";
    public static final String INVALID_TOKEN = TYPE_BASE + "invalid-token";
    public static final String INSUFFICIENT_SCOPE = TYPE_BASE + "insufficient-scope";

    private Problems() {}

    public static Response of(int status, String type, String detail, String requestId, Map<String, ?> extensions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title(status));
        body.put("status", status);
        if (detail != null) {
            body.put("detail", detail);
        }
        body.put("requestId", requestId);
        extensions.forEach(body::putIfAbsent);
        return Response.bytes(status, CONTENT_TYPE, Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    static String title(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 409 -> "Conflict";
            case 412 -> "Precondition Failed";
            case 413 -> "Content Too Large";
            case 415 -> "Unsupported Media Type";
            case 422 -> "Unprocessable Content";
            case 428 -> "Precondition Required";
            case 429 -> "Too Many Requests";
            case 501 -> "Not Implemented";
            case 503 -> "Service Unavailable";
            case 507 -> "Insufficient Storage";
            default -> status >= 500 ? "Internal Server Error" : "Error";
        };
    }
}
