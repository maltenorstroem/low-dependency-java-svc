package com.example.app.web;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * The IETF {@code Idempotency-Key} header. Accepts the Structured Field string form ({@code "..."})
 * and a bare token.
 *
 * <p>Deduplication itself stays in {@code IdempotencyStore}, where it is keyed on a fingerprint of
 * the request and holds the resulting entity. A servlet filter could only dedupe by caching and
 * replaying serialized response bodies, which is both heavier and weaker.
 */
public final class IdempotencyKeys {

    private static final Pattern VALID = Pattern.compile("[!#-\\[\\]-~]{1,255}");

    private IdempotencyKeys() {}

    public static Optional<String> parse(String header) {
        if (header == null) {
            return Optional.empty();
        }
        String key = header.strip();
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            key = key.substring(1, key.length() - 1);
        }
        if (!VALID.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1-255 printable ASCII characters");
        }
        return Optional.of(key);
    }
}
