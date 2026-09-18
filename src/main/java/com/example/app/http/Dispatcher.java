package com.example.app.http;

import com.example.app.domain.CapacityExceededException;
import com.example.app.domain.ConflictException;
import com.example.app.domain.DomainException;
import com.example.app.domain.IdempotencyKeyReusedException;
import com.example.app.domain.NotFoundException;
import com.example.app.domain.ValidationException;
import com.example.app.domain.VersionMismatchException;
import com.example.app.json.JsonException;
import com.example.app.observability.Log;
import com.example.app.observability.Metrics;
import com.example.app.security.AuthException;
import com.example.app.security.Authenticator;
import com.example.app.security.InsufficientScopeException;
import com.example.app.security.InvalidTokenException;
import com.example.app.security.MissingCredentialsException;
import com.example.app.security.Principal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.Headers;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every exchange passes through exactly one {@code try/finally} here. That single choke point
 * guarantees, for every request: a request id, load shedding, authentication, an
 * exception-to-status mapping, security headers, a metric, an access log line, and that the
 * exchange is closed.
 */
public final class Dispatcher implements HttpHandler {

    private static final Log LOG = Log.get(Dispatcher.class);
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._\\-]{1,64}");
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");
    private static final Map<String, String> SECURITY_HEADERS = Map.of(
            "Cache-Control", "no-store",
            "X-Content-Type-Options", "nosniff",
            "X-Frame-Options", "DENY",
            "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'",
            "Referrer-Policy", "no-referrer",
            "Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    private static final List<String> METRIC_METHODS =
            List.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");

    private final Router router;
    private final Metrics metrics;
    private final Authenticator authenticator;
    private final int maxBodyBytes;
    private final Semaphore permits;

    public Dispatcher(Router router, Metrics metrics, Authenticator authenticator, int maxBodyBytes,
            int maxConcurrentRequests) {
        this.router = router;
        this.metrics = metrics;
        this.authenticator = authenticator;
        this.maxBodyBytes = maxBodyBytes;
        this.permits = new Semaphore(maxConcurrentRequests);
    }

    @Override
    public void handle(HttpExchange exchange) {
        long start = System.nanoTime();
        String method = exchange.getRequestMethod();
        String requestId = requestId(exchange);
        Map<String, String> context = new LinkedHashMap<>();
        context.put("requestId", requestId);
        traceId(exchange).ifPresent(traceId -> context.put("traceId", traceId));

        String route = Router.UNMATCHED;
        int status = 500;
        long bytes = 0;
        boolean permitted = permits.tryAcquire();
        AtomicReference<Principal> caller = new AtomicReference<>();
        metrics.requestStarted();

        Log.Scope logContext = Log.bind(context);
        try {
            Response response;
            boolean head = false;
            if (permitted) {
                Router.Match match = router.match(method, exchange.getRequestURI().getRawPath());
                route = match.route();
                head = match instanceof Router.Found found && found.head();
                response = invoke(match, exchange, requestId, caller);
            } else {
                metrics.requestRejected();
                response = Problems.of(503, Problems.BLANK, "Server is at capacity, retry later", requestId, Map.of())
                        .withHeader("Retry-After", "1");
            }
            status = response.status();
            bytes = send(exchange, response, requestId, head);
        } catch (IOException e) {
            LOG.debug("http.response_not_sent", "reason", e.toString()); // client went away
        } catch (RuntimeException e) {
            LOG.error("http.dispatch_failed", e);
        } finally {
            if (permitted) {
                permits.release();
            }
            exchange.close();
            long nanos = System.nanoTime() - start;
            metrics.requestFinished(METRIC_METHODS.contains(method) ? method : "OTHER", route, status, nanos);
            LOG.info("http.request",
                    "method", Request.truncate(method),
                    "path", Request.truncate(exchange.getRequestURI().getRawPath()),
                    "route", route,
                    "status", status,
                    "bytes", bytes,
                    "durationMs", nanos / 1_000_000.0,
                    "sub", Optional.ofNullable(caller.get()).map(Principal::subject).orElse(null),
                    "remote", String.valueOf(exchange.getRemoteAddress()));
            logContext.close();
        }
    }

    private Response invoke(Router.Match match, HttpExchange exchange, String requestId,
            AtomicReference<Principal> caller) {
        try {
            return switch (match) {
                case Router.Found found -> {
                    // Routing first, then authentication: the route table is public information
                    // (it is served at /openapi.yaml), while the existence of an individual
                    // resource is not — and that 404 comes from the handler, which runs only if
                    // this call returns.
                    Optional<Principal> principal = authenticator.authorize(
                            found.access(), exchange.getRequestHeaders().getFirst("Authorization"));
                    principal.ifPresent(caller::set);
                    yield found.handler().handle(new Request(exchange, found.params(), maxBodyBytes, principal));
                }
                case Router.Options options -> Response.empty(204).withHeader("Allow", options.allow());
                case Router.MethodNotAllowed notAllowed -> throw new HttpException(405, "Method not allowed")
                        .withHeader("Allow", notAllowed.allow());
                case Router.NotFound notFound -> throw new HttpException(404, "No such resource");
                case Router.NotImplemented notImplemented -> throw new HttpException(501, "Unknown HTTP method");
            };
        } catch (Exception e) {
            return toProblem(e, requestId, authenticator.realm());
        } catch (StackOverflowError e) {
            LOG.error("http.handler_stack_overflow", e);
            return Problems.of(500, Problems.BLANK, "Internal error", requestId, Map.of());
        }
    }

    /** Maps every failure to RFC 9457. Unexpected errors never leak details to the client. */
    private static Response toProblem(Exception e, String requestId, String realm) {
        return switch (e) {
            case AuthException a -> authProblem(a, requestId, realm);
            case HttpException h -> {
                Response r = Problems.of(h.status(), h.type(), h.getMessage(), requestId, Map.of());
                for (Map.Entry<String, String> header : h.headers().entrySet()) {
                    r = r.withHeader(header.getKey(), header.getValue());
                }
                yield r;
            }
            case JsonException j -> Problems.of(400, Problems.BLANK, "Malformed JSON: " + j.getMessage(), requestId, Map.of());
            case DomainException d -> domainProblem(d, requestId);
            case UncheckedIOException io -> {
                LOG.warn("http.request_read_failed", "reason", io.getCause().toString());
                yield Problems.of(400, Problems.BLANK, "Could not read request", requestId, Map.of());
            }
            default -> {
                LOG.error("http.unhandled_exception", e);
                yield Problems.of(500, Problems.BLANK, "Internal error; quote the requestId when reporting", requestId, Map.of());
            }
        };
    }

    /**
     * RFC 6750 section 3. The {@code error_description} is always one of a fixed set of phrases:
     * no parsed token content, no JCA message and no configured URL ever reaches it, so the
     * endpoint cannot be used to probe which part of a forged token was wrong.
     */
    private static Response authProblem(AuthException e, String requestId, String realm) {
        String challenge = "Bearer realm=\"" + realm + "\"";
        return switch (e) {
            // No credentials at all: RFC 6750 says not to send an error code, because there is no
            // failed attempt to report on.
            case MissingCredentialsException m ->
                    Problems.of(401, Problems.BLANK, m.getMessage(), requestId, Map.of())
                            .withHeader("WWW-Authenticate", challenge);
            // We could not reach the identity provider, so we cannot say anything about this
            // token. That is our failure, not the caller's: 503 invites a retry, where 401 would
            // send a client with a perfectly good token away to get another one.
            case InvalidTokenException k when k.cause() == InvalidTokenException.Reason.KEYS_UNAVAILABLE ->
                    Problems.of(503, Problems.BLANK, "Cannot verify tokens right now, retry later",
                                    requestId, Map.of())
                            .withHeader("Retry-After", "5");
            case InsufficientScopeException i ->
                    Problems.of(403, Problems.INSUFFICIENT_SCOPE, i.getMessage(), requestId,
                                    Map.of("requiredScopes", i.required().stream().sorted().toList()))
                            .withHeader("WWW-Authenticate", challenge
                                    + ", error=\"insufficient_scope\""
                                    + ", error_description=\"" + i.getMessage() + "\""
                                    + ", scope=\"" + i.requiredAsParameter() + "\"");
            default ->
                    Problems.of(401, Problems.INVALID_TOKEN, e.getMessage(), requestId, Map.of())
                            .withHeader("WWW-Authenticate", challenge
                                    + ", error=\"invalid_token\""
                                    + ", error_description=\"" + e.getMessage() + "\"");
        };
    }

    // Exhaustive over the sealed hierarchy: a new domain failure will not compile until mapped here.
    private static Response domainProblem(DomainException d, String requestId) {
        return switch (d) {
            case NotFoundException n -> Problems.of(404, Problems.BLANK, n.getMessage(), requestId, Map.of());
            case ValidationException v -> Problems.of(422, Problems.VALIDATION, v.getMessage(), requestId,
                    Map.of("errors", v.violations().stream()
                            .map(x -> Map.of("pointer", x.pointer(), "detail", x.message()))
                            .toList()));
            case VersionMismatchException v -> Problems.of(412, Problems.VERSION_MISMATCH,
                    "The resource has changed; GET it again and retry with the new ETag", requestId, Map.of());
            case ConflictException c -> Problems.of(409, Problems.CONFLICT, c.getMessage(), requestId, Map.of());
            case IdempotencyKeyReusedException i ->
                    Problems.of(422, Problems.IDEMPOTENCY_KEY_REUSED, i.getMessage(), requestId, Map.of());
            case CapacityExceededException c ->
                    Problems.of(507, Problems.CAPACITY_EXCEEDED, c.getMessage(), requestId, Map.of());
        };
    }

    private static long send(HttpExchange exchange, Response response, String requestId, boolean head)
            throws IOException {
        Headers headers = exchange.getResponseHeaders();
        SECURITY_HEADERS.forEach(headers::set);
        headers.set("X-Request-Id", requestId);
        response.headers().forEach(headers::set);
        byte[] body = response.body();
        int status = response.status();
        if (body.length > 0) {
            headers.set("Content-Type", response.contentType());
        }
        boolean noBody = head || body.length == 0 || status == 204 || status == 304;
        if (head && body.length > 0) {
            headers.set("Content-Length", Integer.toString(body.length));
        }
        // -1 = no body (JDK convention); 0 would mean chunked encoding.
        exchange.sendResponseHeaders(status, noBody ? -1 : body.length);
        if (noBody) {
            return 0;
        }
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        return body.length;
    }

    /** Propagates a caller's X-Request-Id only when it is safe to log; otherwise generates one. */
    private static String requestId(HttpExchange exchange) {
        String incoming = exchange.getRequestHeaders().getFirst("X-Request-Id");
        if (incoming != null && REQUEST_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    /** W3C Trace Context: extracts the trace id so logs correlate with distributed traces. */
    private static Optional<String> traceId(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("traceparent");
        if (header == null) {
            return Optional.empty();
        }
        Matcher m = TRACEPARENT.matcher(header.strip());
        if (!m.matches() || m.group(1).chars().allMatch(c -> c == '0') || m.group(2).chars().allMatch(c -> c == '0')) {
            return Optional.empty();
        }
        return Optional.of(m.group(1));
    }
}
