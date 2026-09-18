package com.example.app.domain;

/**
 * Business-rule failures. The hierarchy is sealed so the HTTP layer's mapping to status codes is
 * checked for exhaustiveness by the compiler: adding a new failure without mapping it will not
 * compile.
 */
public abstract sealed class DomainException extends RuntimeException
        permits NotFoundException,
                ValidationException,
                VersionMismatchException,
                ConflictException,
                IdempotencyKeyReusedException,
                CapacityExceededException {

    private static final long serialVersionUID = 1L;

    protected DomainException(String message) {
        // Stack traces are not needed for expected outcomes and cost time under load.
        super(message, null, false, false);
    }
}
