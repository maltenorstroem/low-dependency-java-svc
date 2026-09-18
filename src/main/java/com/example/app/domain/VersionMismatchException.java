package com.example.app.domain;

/** Optimistic concurrency check failed: the resource was modified by someone else. */
public final class VersionMismatchException extends DomainException {
    private static final long serialVersionUID = 1L;

    public VersionMismatchException(String message) {
        super(message);
    }
}
