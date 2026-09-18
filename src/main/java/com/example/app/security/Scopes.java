package com.example.app.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scope extraction. Providers disagree: {@code scope} holds a space-delimited string (RFC 6749,
 * RFC 9068, Keycloak), while {@code scp} holds an array (Entra ID) or sometimes the same
 * space-delimited string. Read both and take the union rather than guessing at the provider.
 */
public final class Scopes {

    private static final int MAX_SCOPES = 64;
    private static final int MAX_SCOPE_LENGTH = 128;

    private Scopes() {}

    public static Set<String> extract(Map<String, Object> claims) {
        Set<String> scopes = new LinkedHashSet<>();
        add(scopes, claims.get("scope"));
        add(scopes, claims.get("scp"));
        return Set.copyOf(scopes);
    }

    private static void add(Set<String> into, Object claim) {
        switch (claim) {
            case String text -> {
                for (String candidate : text.split(" ")) {
                    offer(into, candidate);
                }
            }
            case List<?> list -> {
                for (Object element : list) {
                    if (element instanceof String text) {
                        offer(into, text);
                    }
                }
            }
            case null, default -> { /* absent or an unexpected shape: contributes nothing */ }
        }
    }

    /** A malformed entry is dropped, not fatal: one odd scope must not invalidate a good token. */
    private static void offer(Set<String> into, String candidate) {
        if (candidate.isEmpty() || candidate.length() > MAX_SCOPE_LENGTH || into.size() >= MAX_SCOPES) {
            return;
        }
        if (isScopeToken(candidate)) {
            into.add(candidate);
        }
    }

    /** The {@code scope-token} production of RFC 6749: visible ASCII except the double quote and backslash. */
    private static boolean isScopeToken(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != 0x21 && !(c >= 0x23 && c <= 0x5B) && !(c >= 0x5D && c <= 0x7E)) {
                return false;
            }
        }
        return true;
    }
}
