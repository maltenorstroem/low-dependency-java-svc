package com.example.app.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Which scopes a request needs, as one table.
 *
 * <p>It exists because Spring's {@code AccessDeniedHandler} is told that access was denied but not
 * what would have been required, and the contract puts that in the 403 body so a caller can act on
 * it. The same table configures {@code authorizeHttpRequests}, so the answer and the enforcement
 * cannot drift apart.
 */
public class RequiredScopes {

    /** One rule: a method, a path pattern, and the scope it requires. */
    public record Rule(HttpMethod method, PathPattern pattern, String scope) {}

    private final List<Rule> rules;

    public RequiredScopes(ScopeNames scopes) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.rules = List.of(
                rule(parser, HttpMethod.GET, "/v1/tasks", scopes.tasksRead()),
                rule(parser, HttpMethod.GET, "/v1/tasks/{id}", scopes.tasksRead()),
                rule(parser, HttpMethod.POST, "/v1/tasks", scopes.tasksWrite()),
                rule(parser, HttpMethod.PUT, "/v1/tasks/{id}", scopes.tasksWrite()),
                rule(parser, HttpMethod.DELETE, "/v1/tasks/{id}", scopes.tasksWrite()),
                rule(parser, HttpMethod.GET, "/v1/cubes", scopes.cubesRead()),
                rule(parser, HttpMethod.GET, "/v1/cubes/{id}", scopes.cubesRead()),
                rule(parser, HttpMethod.GET, "/v1/cubes/{id}/pdf", scopes.cubesRead()),
                rule(parser, HttpMethod.POST, "/v1/cubes", scopes.cubesWrite()),
                rule(parser, HttpMethod.PUT, "/v1/cubes/{id}", scopes.cubesWrite()),
                rule(parser, HttpMethod.DELETE, "/v1/cubes/{id}", scopes.cubesWrite()));
    }

    private static Rule rule(PathPatternParser parser, HttpMethod method, String pattern, String scope) {
        return new Rule(method, parser.parse(pattern), scope);
    }

    public List<Rule> rules() {
        return rules;
    }

    /** HEAD inherits GET's requirement, as the router does. */
    public Set<String> forRequest(HttpServletRequest request) {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        if (HttpMethod.HEAD.equals(method)) {
            method = HttpMethod.GET;
        }
        var path = org.springframework.http.server.PathContainer.parsePath(
                request.getRequestURI() == null ? "" : request.getRequestURI());
        for (Rule rule : rules) {
            if (rule.method().equals(method) && rule.pattern().matches(path)) {
                return Set.of(rule.scope());
            }
        }
        return Set.of();
    }
}
