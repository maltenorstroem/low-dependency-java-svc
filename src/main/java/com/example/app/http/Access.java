package com.example.app.http;

import java.util.Set;

/**
 * The access level a route requires, declared where the route is registered.
 *
 * <p>Every {@code Router} registration must supply one: there is no default and no overload that
 * omits it, so a new route cannot silently ship unprotected. Forgetting to decide is a compile
 * error, which is the only kind of reminder that never gets lost.
 */
public sealed interface Access {

    /** No credentials required. Health checks, metrics and the published contract. */
    Access PUBLIC = new Anonymous();

    record Anonymous() implements Access {}

    /** Any valid token, whatever its scopes. */
    record Authenticated() implements Access {}

    /** A valid token that carries every one of {@code allOf}. */
    record RequiresScope(Set<String> allOf) implements Access {
        public RequiresScope {
            if (allOf.isEmpty()) {
                throw new IllegalArgumentException("Use Access.authenticated() for no scope requirement");
            }
            allOf = Set.copyOf(allOf);
        }
    }

    static Access scope(String... scopes) {
        return new RequiresScope(Set.of(scopes));
    }

    static Access authenticated() {
        return new Authenticated();
    }
}
