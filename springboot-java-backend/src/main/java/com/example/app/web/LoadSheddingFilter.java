package com.example.app.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounds how many requests are in flight at once, answering 503 beyond that.
 *
 * <p>With virtual threads enabled, Tomcat's {@code maxThreads} no longer bounds concurrency, so
 * this semaphore is the only backpressure the service has: without it, an overload becomes memory
 * pressure and then an OutOfMemoryError instead of a fast, honest refusal.
 *
 * <p>It runs ahead of the security chain, because shedding before verifying a token — which may
 * mean fetching signing keys — is the entire point. Not a rate limiter: Bucket4j and friends limit
 * per client, which this service deliberately does not do. Not Resilience4j's Bulkhead either,
 * which is the right abstraction at roughly a hundred times the weight of a Semaphore.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoadSheddingFilter extends OncePerRequestFilter {

    private final Semaphore permits;
    private final Counter rejected;
    private final ObjectMapper mapper;

    public LoadSheddingFilter(int maxConcurrentRequests, MeterRegistry registry, ObjectMapper mapper) {
        this.permits = new Semaphore(maxConcurrentRequests);
        this.rejected = Counter.builder("http_server_requests_rejected_total")
                .description("Requests refused because the concurrency limit was reached")
                .register(registry);
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        if (!permits.tryAcquire()) {
            rejected.increment();
            shed(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }

    private void shed(HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Server is at capacity, retry later");
        problem.setTitle(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        response.getWriter().write(mapper.writeValueAsString(problem));
    }
}
