package com.example.app.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Scope extraction. Providers disagree: {@code scope} holds a space-delimited string (RFC 6749,
 * RFC 9068, Keycloak), while {@code scp} holds an array (Entra ID) or sometimes the same
 * space-delimited string. Read both and take the union rather than guessing at the provider.
 *
 * <p>The counts are bounded so a hostile or merely broken token cannot inflate the authority set
 * that every later authorization check walks.
 */
public class ScopeAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final int MAX_SCOPES = 64;
    private static final int MAX_SCOPE_LENGTH = 128;

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<String> scopes = new LinkedHashSet<>();
        add(scopes, jwt.getClaim("scope"));
        add(scopes, jwt.getClaim("scp"));

        List<GrantedAuthority> authorities = new ArrayList<>(scopes.size());
        for (String scope : scopes) {
            // No SCOPE_ prefix: ScopeNames already produces the full authority, and the
            // configuration compares it with hasAuthority.
            authorities.add(new SimpleGrantedAuthority(scope));
        }
        return authorities;
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
            case null, default -> {
                // no scopes in this claim
            }
        }
    }

    private static void offer(Set<String> into, String scope) {
        if (!scope.isBlank() && scope.length() <= MAX_SCOPE_LENGTH && into.size() < MAX_SCOPES) {
            into.add(scope);
        }
    }
}
