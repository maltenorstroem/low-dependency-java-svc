package com.example.app.security;

import com.example.app.web.ApiExceptionHandler;
import com.example.app.web.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 403 responses as RFC 9457 problem documents, naming the scopes the caller would have needed both
 * in the body and in the RFC 6750 challenge. Telling a caller which scope is missing is not a leak:
 * the scope names are in the published contract.
 */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final String realm;
    private final RequiredScopes requiredScopes;
    private final AuthMetrics metrics;
    private final tools.jackson.databind.ObjectMapper mapper;

    public ProblemAccessDeniedHandler(String realm, RequiredScopes requiredScopes, AuthMetrics metrics,
            tools.jackson.databind.ObjectMapper mapper) {
        this.realm = realm;
        this.requiredScopes = requiredScopes;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException failure) throws IOException {

        metrics.record(AuthMetrics.Outcome.INSUFFICIENT_SCOPE);
        Set<String> needed = new TreeSet<>(requiredScopes.forRequest(request));

        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"" + realm + "\", "
                + "error=\"insufficient_scope\", scope=\"" + String.join(" ", needed) + "\"");

        Object requestId = request.getAttribute(ApiExceptionHandler.REQUEST_ID);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", ProblemTypes.INSUFFICIENT_SCOPE.toString());
        body.put("title", HttpStatus.FORBIDDEN.getReasonPhrase());
        body.put("status", HttpStatus.FORBIDDEN.value());
        body.put("detail", "The access token does not carry the required scope");
        body.put(ApiExceptionHandler.REQUEST_ID, requestId == null ? "-" : requestId.toString());
        body.put("requiredScopes", needed);

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
