package com.example.app.security;

import com.example.app.http.Access;
import java.util.Objects;

/**
 * The scope names this API requires, built from a configurable prefix.
 *
 * <p>The prefix exists because scopes live in a namespace the identity provider owns, not this
 * service: in a shared Keycloak realm a bare {@code tasks:read} would collide with every other
 * service that had the same idea. Configuring it, rather than hard-coding one, means the same
 * build drops into realms that disagree about naming.
 */
public record ScopeNames(String prefix) {

    public ScopeNames {
        Objects.requireNonNull(prefix, "prefix");
    }

    public Access tasksRead() {
        return Access.scope(prefix + "tasks:read");
    }

    public Access tasksWrite() {
        return Access.scope(prefix + "tasks:write");
    }

    public Access cubesRead() {
        return Access.scope(prefix + "cubes:read");
    }

    public Access cubesWrite() {
        return Access.scope(prefix + "cubes:write");
    }
}
