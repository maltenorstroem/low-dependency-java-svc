package com.example.app.domain;

/** The request conflicts with the current state, e.g. an identical request is still in progress. */
public final class ConflictException extends DomainException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
