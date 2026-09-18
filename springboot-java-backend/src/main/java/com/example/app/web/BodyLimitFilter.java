package com.example.app.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Caps the size of a request body and refuses encodings the service will not decode.
 *
 * <p>This has to be written out: Tomcat's {@code maxPostSize} applies to form-encoded content and
 * {@code spring.servlet.multipart.max-request-size} to multipart, and neither covers a raw
 * {@code application/json} body — which is the only kind this service accepts. Without it the only
 * limit on a POST is available memory.
 *
 * <p>A declared {@code Content-Length} is refused immediately; a chunked body is counted as it is
 * read, so a request that lies about its length is still stopped at the limit rather than after it.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BodyLimitFilter extends OncePerRequestFilter {

    private final int maxBodyBytes;
    private final ObjectMapper mapper;

    public BodyLimitFilter(int maxBodyBytes, ObjectMapper mapper) {
        this.maxBodyBytes = maxBodyBytes;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String encoding = request.getHeader(HttpHeaders.CONTENT_ENCODING);
        if (encoding != null && !encoding.strip().toLowerCase(Locale.ROOT).equals("identity")) {
            problem(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Content-Encoding must be identity", HttpHeaders.ACCEPT_ENCODING, "identity");
            return;
        }
        if (request.getContentLengthLong() > maxBodyBytes) {
            tooLarge(response);
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBodyBytes), response);
    }

    private void tooLarge(HttpServletResponse response) throws IOException {
        // The remainder of the body is never read, so the connection cannot be reused for the next
        // request on it; saying so is more honest than leaving the client to discover it.
        problem(response, HttpStatus.CONTENT_TOO_LARGE,
                "Request body must not exceed " + maxBodyBytes + " bytes", HttpHeaders.CONNECTION, "close");
    }

    private void problem(HttpServletResponse response, HttpStatus status, String detail,
            String headerName, String headerValue) throws IOException {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(headerName, headerValue);
        response.getWriter().write(mapper.writeValueAsString(problem));
    }

    /** Wraps the request so the body is counted as it is consumed. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final int limit;

        LimitedRequest(HttpServletRequest request, int limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {
                private long count;

                private int count(int read) throws IOException {
                    if (read >= 0 && ++count > limit) {
                        throw new BodyTooLargeException(limit);
                    }
                    return read;
                }

                @Override
                public int read() throws IOException {
                    return count(delegate.read());
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int read = delegate.read(buffer, offset, length);
                    if (read > 0) {
                        count += read;
                        if (count > limit) {
                            throw new BodyTooLargeException(limit);
                        }
                    }
                    return read;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
