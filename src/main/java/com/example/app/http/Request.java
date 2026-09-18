package com.example.app.http;

import com.example.app.json.Json;
import com.example.app.security.Principal;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A read-only view of the incoming request with strict, bounded parsing. */
public final class Request {

    private final HttpExchange exchange;
    private final Map<String, String> pathParams;
    private final int maxBodyBytes;
    private final Optional<Principal> principal;
    private byte[] body;

    Request(HttpExchange exchange, Map<String, String> pathParams, int maxBodyBytes,
            Optional<Principal> principal) {
        this.exchange = exchange;
        this.pathParams = Map.copyOf(pathParams);
        this.maxBodyBytes = maxBodyBytes;
        this.principal = principal;
    }

    public String method() {
        return exchange.getRequestMethod();
    }

    /**
     * The verified caller, present only on a route that required authentication. Handlers that
     * need to record who did something, or to scope a query to one tenant, read it from here.
     */
    public Optional<Principal> principal() {
        return principal;
    }

    public String pathParam(String name) {
        String value = pathParams.get(name);
        if (value == null) {
            throw new IllegalStateException("No path parameter " + name);
        }
        return value;
    }

    /** All values of a header, combined as a comma-separated list (RFC 9110 section 5.3). */
    public Optional<String> header(String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join(", ", values));
    }

    /** Query parameters. Malformed encoding and repeated names are rejected rather than guessed at. */
    public Map<String, String> query() {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : raw.split("&", -1)) {
            if (pair.isEmpty()) {
                throw new HttpException(400, "Empty query parameter");
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            if (params.putIfAbsent(name, value) != null) {
                throw new HttpException(400, "Query parameter '" + truncate(name) + "' is repeated");
            }
        }
        return Collections.unmodifiableMap(params);
    }

    public void requireQueryParams(Map<String, String> query, List<String> allowed) {
        for (String name : query.keySet()) {
            if (!allowed.contains(name)) {
                throw new HttpException(400, "Unknown query parameter '" + truncate(name) + "'");
            }
        }
    }

    public void requireAcceptsJson() {
        if (!MediaTypes.acceptsJson(header("Accept").orElse(null))) {
            throw new HttpException(406, "This resource can only be represented as application/json");
        }
    }

    /** For representations that are not JSON, such as a generated document. */
    public void requireAccepts(String type) {
        if (!MediaTypes.accepts(header("Accept").orElse(null), type)) {
            throw new HttpException(406, "This resource can only be represented as " + type);
        }
    }

    /** Parses the body as JSON after checking media type, encoding, size and UTF-8 validity. */
    public Object jsonBody() {
        if (!MediaTypes.isJson(header("Content-Type").orElse(null))) {
            throw new HttpException(415, "Content-Type must be application/json")
                    .withHeader("Accept-Post", Response.JSON);
        }
        String encoding = header("Content-Encoding").orElse("identity").strip();
        if (!encoding.equalsIgnoreCase("identity")) {
            throw new HttpException(415, "Content-Encoding is not supported").withHeader("Accept-Encoding", "identity");
        }
        byte[] bytes = body();
        if (bytes.length == 0) {
            throw new HttpException(400, "Request body is required");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new HttpException(400, "Request body is not valid UTF-8");
        }
        return Json.parse(text);
    }

    /** The raw body, at most {@code maxBodyBytes}; larger bodies fail with 413 without being buffered. */
    public byte[] body() {
        if (body != null) {
            return body;
        }
        Optional<String> declared = header("Content-Length");
        if (declared.isPresent()) {
            try {
                if (Long.parseLong(declared.get().strip()) > maxBodyBytes) {
                    throw tooLarge();
                }
            } catch (NumberFormatException e) {
                throw new HttpException(400, "Invalid Content-Length");
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] data = in.readNBytes(maxBodyBytes + 1);
            if (data.length > maxBodyBytes) {
                throw tooLarge();
            }
            body = data;
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read request body", e);
        }
    }

    private HttpException tooLarge() {
        return new HttpException(413, "Request body exceeds " + maxBodyBytes + " bytes")
                .withHeader("Connection", "close");
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new HttpException(400, "Malformed query string");
        }
    }

    static String truncate(String value) {
        String cleaned = value.codePoints()
                .filter(cp -> !Character.isISOControl(cp))
                .limit(64)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        return cleaned.length() < value.length() ? cleaned + "..." : cleaned;
    }
}
