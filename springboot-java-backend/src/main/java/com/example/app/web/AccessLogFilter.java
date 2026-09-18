package com.example.app.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * One line per request. {@code route} is the mapping template rather than the path, so the field
 * stays bounded no matter what identifiers callers send.
 *
 * <p>{@code bytes} is taken from {@code Content-Length}; wrapping the output stream to count it
 * exactly would buffer every response for a number nothing depends on.
 */
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger("http.request");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long nanos = System.nanoTime() - start;
            LOG.atInfo()
                    .setMessage("http.request")
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("route", route(request))
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("bytes", bytes(response))
                    .addKeyValue("durationMs", nanos / 1_000_000.0)
                    .addKeyValue("sub", subject())
                    .addKeyValue("remote", request.getRemoteAddr())
                    .log();
        }
    }

    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? "unmatched" : pattern.toString();
    }

    private static long bytes(HttpServletResponse response) {
        String length = response.getHeader("Content-Length");
        try {
            return length == null ? -1 : Long.parseLong(length);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String subject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null; // "anonymousUser" is Spring's placeholder, not a subject anyone can look up
        }
        return authentication.getName();
    }
}
