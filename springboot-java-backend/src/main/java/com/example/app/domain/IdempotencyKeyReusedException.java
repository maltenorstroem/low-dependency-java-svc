package com.example.app.domain;

/** An idempotency key was reused with a different request payload. */
public final class IdempotencyKeyReusedException extends DomainException {
    private static final long serialVersionUID = 1L;

    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
