package com.example.app.domain;

import java.util.List;

/** Input is well-formed but violates business rules. Carries every violation, not just the first. */
public final class ValidationException extends DomainException {
    private static final long serialVersionUID = 1L;

    /** A single violation. {@code pointer} is a JSON Pointer (RFC 6901) into the request body. */
    public record Violation(String pointer, String message) {}

    private final transient List<Violation> violations;

    public ValidationException(List<Violation> violations) {
        super("Request validation failed");
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }
}
