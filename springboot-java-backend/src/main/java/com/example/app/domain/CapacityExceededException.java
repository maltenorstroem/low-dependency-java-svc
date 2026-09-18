package com.example.app.domain;

/** A hard storage limit was reached. Every in-memory structure in this service is bounded. */
public final class CapacityExceededException extends DomainException {
    private static final long serialVersionUID = 1L;

    public CapacityExceededException(String message) {
        super(message);
    }
}
