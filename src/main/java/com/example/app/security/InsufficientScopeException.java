package com.example.app.security;

import java.util.Set;
import java.util.TreeSet;

/**
 * The token is valid but does not carry the scopes this route requires. Unlike the invalid-token
 * cases, the required scopes are returned: RFC 6750 defines the {@code scope} parameter precisely
 * so a client can go and ask its identity provider for the right token.
 */
public final class InsufficientScopeException extends AuthException {

    private static final long serialVersionUID = 1L;

    private final transient Set<String> required;

    public InsufficientScopeException(Set<String> required) {
        super("insufficient_scope", "The request requires higher privileges");
        this.required = new TreeSet<>(required); // sorted, so the response is deterministic
    }

    public Set<String> required() {
        return Set.copyOf(required);
    }

    /** Space-delimited, as the {@code scope} parameter of RFC 6750 requires. */
    public String requiredAsParameter() {
        return String.join(" ", required);
    }
}
