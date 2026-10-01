package com.example.app.security;

import java.util.Objects;

/**
 * The scope names this API requires, built from a configurable prefix.
 *
 * <p>The prefix exists because scopes live in a namespace the identity provider owns, not this
 * service: in a shared Keycloak realm a bare {@code tasks:read} would collide with every other
 * service that had the same idea. Configuring it, rather than hard-coding one, means the same
 * build drops into realms that disagree about naming.
 *
 * <p>These are used directly as Spring Security authorities, which is why the converter maps scopes
 * with an empty authority prefix instead of the usual {@code SCOPE_}: the full name is the
 * authority.
 */
public record ScopeNames(String prefix) {

    public ScopeNames {
        Objects.requireNonNull(prefix, "prefix");
    }

    public String tasksRead() {
        return prefix + "tasks:read";
    }

    public String tasksWrite() {
        return prefix + "tasks:write";
    }

    public String cubesRead() {
        return prefix + "cubes:read";
    }

    public String cubesWrite() {
        return prefix + "cubes:write";
    }

    public String stringsRead() {
        return prefix + "strings:read";
    }
}
