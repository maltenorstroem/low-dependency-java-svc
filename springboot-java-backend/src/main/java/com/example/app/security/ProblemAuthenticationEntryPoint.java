package com.example.app.security;

import com.example.app.web.ApiExceptionHandler;
import com.example.app.web.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import com.nimbusds.jose.jwk.source.JWKSetRetrievalException;
import com.nimbusds.jose.jwk.source.JWKSetUnavailableException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 401 responses as RFC 9457 problem documents, with the RFC 6750 challenge.
 *
 * <p>Three cases, and the distinction matters to a caller deciding what to do next:
 * no credentials at all gets a bare challenge with no {@code error} code, as RFC 6750 requires; an
 * unusable token says {@code invalid_token}; and a token that could not be checked because the
 * identity provider is unreachable is 503, not 401 — the caller did nothing wrong and should retry
 * rather than go and fetch a new token.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final String realm;
    private final AuthMetrics metrics;
    private final tools.jackson.databind.ObjectMapper mapper;

    public ProblemAuthenticationEntryPoint(String realm, AuthMetrics metrics,
            tools.jackson.databind.ObjectMapper mapper) {
        this.realm = realm;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException failure) throws IOException {

        if (keysUnavailable(failure)) {
            metrics.record(AuthMetrics.Outcome.KEYS_UNAVAILABLE);
            ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, null,
                    "Unable to verify credentials, retry later");
            response.setHeader(HttpHeaders.RETRY_AFTER, "5");
            write(request, response, HttpStatus.SERVICE_UNAVAILABLE, problem);
            return;
        }

        String errorCode = errorCode(failure);
        if (errorCode == null) {
            metrics.record(AuthMetrics.Outcome.MISSING_TOKEN);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"" + realm + "\"");
            ProblemDetail problem = problem(HttpStatus.UNAUTHORIZED, null, "Authentication is required");
            write(request, response, HttpStatus.UNAUTHORIZED, problem);
            return;
        }

        metrics.record(AuthMetrics.Outcome.INVALID_TOKEN);
        // The description says why the token failed, never what it contained.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"" + realm + "\", "
                + "error=\"invalid_token\", error_description=\"" + description(failure) + "\"");
        ProblemDetail problem = problem(HttpStatus.UNAUTHORIZED, ProblemTypes.INVALID_TOKEN,
                "The access token is not valid");
        write(request, response, HttpStatus.UNAUTHORIZED, problem);
    }

    /**
     * A token that could not be checked is not a token that was rejected. Spring reports both as an
     * invalid token, so the cause chain is what separates them.
     */
    private static boolean keysUnavailable(AuthenticationException failure) {
        // Spring reserves AuthenticationServiceException for infrastructure that did not work, as
        // opposed to credentials that were wrong; Nimbus names the specific case underneath. Both
        // are matched on type rather than on message text, which is not part of any contract.
        if (failure instanceof AuthenticationServiceException) {
            return true;
        }
        for (Throwable t = failure; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof JWKSetRetrievalException || t instanceof JWKSetUnavailableException) {
                return true;
            }
        }
        return false;
    }

    private static String errorCode(AuthenticationException failure) {
        if (failure instanceof OAuth2AuthenticationException oauth) {
            String code = oauth.getError().getErrorCode();
            // RFC 6750: a request with no credentials gets a challenge without an error code.
            return BearerTokenErrorCodes.INVALID_TOKEN.equals(code) ? code : null;
        }
        return null;
    }

    private static String description(AuthenticationException failure) {
        if (failure instanceof OAuth2AuthenticationException oauth) {
            String description = oauth.getError().getDescription();
            if (description != null) {
                // Header-safe and short; the full reason is in the logs.
                String cleaned = description.replaceAll("[^\\x20-\\x7E]", "").replace("\"", "'");
                return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
            }
        }
        return "The access token is not valid";
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (type != null) {
            problem.setType(type);
        }
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            ProblemDetail problem) throws IOException {

        Object requestId = request.getAttribute(ApiExceptionHandler.REQUEST_ID);
        Map<String, Object> body = new LinkedHashMap<>();
        // getType() is null until one is set; about:blank is what RFC 9457 means by "no type".
        body.put("type", problem.getType() == null ? "about:blank" : problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", status.value());
        body.put("detail", problem.getDetail());
        body.put(ApiExceptionHandler.REQUEST_ID, requestId == null ? "-" : requestId.toString());
        if (problem.getProperties() != null) {
            problem.getProperties().forEach(body::putIfAbsent);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
