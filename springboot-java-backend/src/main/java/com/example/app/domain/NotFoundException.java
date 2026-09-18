package com.example.app.domain;

/** The requested resource does not exist. */
public final class NotFoundException extends DomainException {
    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }
}
