package com.example.app.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id and puts it, plus any incoming trace id, into the logging context.
 *
 * <p>Deliberately not Micrometer Tracing: this service only reads {@code traceparent} so log lines
 * can be correlated with whatever produced them. Creating spans would mean a tracer bridge, an
 * exporter and a collector to send them to, none of which exist here. The upgrade path is to add
 * micrometer-tracing-bridge-otel and delete the parsing below.
 *
 * <p>Named for what it does rather than for the context it populates, because Spring MVC already
 * auto-configures a bean called {@code requestContextFilter} and two beans cannot share a name.
 *
 * <p>Ordered just after load shedding, so a shed request still carries an id.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** Echoed back to the caller and written to logs, so the accepted shape is narrow. */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._\\-]{1,64}");
    private static final Pattern TRACEPARENT =
            Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String requestId = requestId(request);
        request.setAttribute(ApiExceptionHandler.REQUEST_ID, requestId);
        response.setHeader(HEADER, requestId);

        MDC.put("requestId", requestId);
        String traceId = traceId(request);
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
            MDC.remove("traceId");
        }
    }

    private static String requestId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER);
        // An id that is echoed into a header and a log line is an injection vector if it is taken
        // on trust, so anything that is not plainly safe is replaced rather than sanitized.
        return incoming != null && SAFE_REQUEST_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
    }

    private static String traceId(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        if (traceparent == null) {
            return null;
        }
        var matcher = TRACEPARENT.matcher(traceparent);
        if (!matcher.matches()) {
            return null;
        }
        String traceId = matcher.group(1);
        return traceId.chars().allMatch(c -> c == '0') ? null : traceId; // all-zero is "no trace"
    }
}
