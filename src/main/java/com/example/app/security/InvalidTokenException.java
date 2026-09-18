package com.example.app.security;

import java.util.Locale;

/**
 * A token was presented but could not be trusted.
 *
 * <p>Only {@code EXPIRED} and {@code MALFORMED} get a distinguishing description, because those
 * two are the ones an honest client can act on. Every other cause collapses to a single message,
 * so the endpoint cannot be used as an oracle to probe which part of a forged token was wrong.
 * The precise {@link #reason()} is logged and counted instead.
 */
public final class InvalidTokenException extends AuthException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        MALFORMED("The access token is malformed"),
        EXPIRED("The access token is expired"),
        NOT_YET_VALID("The access token is not valid"),
        BAD_SIGNATURE("The access token is not valid"),
        UNKNOWN_KEY("The access token is not valid"),
        BAD_ALGORITHM("The access token is not valid"),
        BAD_ISSUER("The access token is not valid"),
        BAD_AUDIENCE("The access token is not valid"),
        MISSING_CLAIM("The access token is not valid"),
        TOO_LARGE("The access token is not valid"),
        KEYS_UNAVAILABLE("The access token is not valid");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private final transient Reason cause;

    public InvalidTokenException(Reason cause) {
        super(cause.name().toLowerCase(Locale.ROOT), cause.description());
        this.cause = cause;
    }

    public Reason cause() {
        return cause;
    }
}
