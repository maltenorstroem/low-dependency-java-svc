package com.example.app.security;

/**
 * Authentication and authorisation failures, sealed so the dispatcher's mapping to a status code
 * stays exhaustive — the same guarantee {@code DomainException} gives the business failures.
 *
 * <p>The message of every subclass is client-safe by construction: it comes from a fixed
 * vocabulary, never from a parsed token, a JCA exception or a configured URL. No subclass holds a
 * reference to the token.
 */
public sealed class AuthException extends RuntimeException
        permits MissingCredentialsException, InvalidTokenException, InsufficientScopeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    AuthException(String reason, String description) {
        super(description, null, false, false); // no stack trace: these are expected, and frequent
        this.reason = reason;
    }

    /** A short, stable code for logs and metrics. Never sent to the client. */
    public final String reason() {
        return reason;
    }
}
