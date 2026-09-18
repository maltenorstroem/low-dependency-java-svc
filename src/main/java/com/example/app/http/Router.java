package com.example.app.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Explicit, table-driven routing. Templates are literal segments and {@code {name}} parameters,
 * matched exactly (no trailing-slash or case guessing). Distinguishes 404, 405 (with {@code Allow})
 * and 501 as RFC 9110 requires, and answers OPTIONS and HEAD for every route.
 */
public final class Router {

    private static final Set<String> KNOWN_METHODS =
            Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE", "CONNECT");

    public static final String UNMATCHED = "unmatched";

    /** Result of matching a request. {@code route} is the template, safe to use as a metric label. */
    public sealed interface Match {
        String route();
    }

    public record Found(String route, Handler handler, Access access, Map<String, String> params, boolean head)
            implements Match {}

    public record Options(String route, String allow) implements Match {}

    public record MethodNotAllowed(String route, String allow) implements Match {}

    public record NotFound(String route) implements Match {}

    public record NotImplemented(String route) implements Match {}

    private record Endpoint(Handler handler, Access access) {}

    private record Route(String template, String[] segments, Map<String, Endpoint> handlers) {}

    private final List<Route> routes = new ArrayList<>();

    public Router get(String template, Access access, Handler handler) {
        return add("GET", template, access, handler);
    }

    public Router post(String template, Access access, Handler handler) {
        return add("POST", template, access, handler);
    }

    public Router put(String template, Access access, Handler handler) {
        return add("PUT", template, access, handler);
    }

    public Router delete(String template, Access access, Handler handler) {
        return add("DELETE", template, access, handler);
    }

    /**
     * There is deliberately no overload without {@code access}. A route that nobody decided the
     * access level for does not compile, which is the only kind of reminder that cannot be
     * forgotten during review.
     */
    public synchronized Router add(String method, String template, Access access, Handler handler) {
        if (!template.startsWith("/") || template.contains("//")) {
            throw new IllegalArgumentException("Invalid route template " + template);
        }
        Route route = routes.stream().filter(r -> r.template().equals(template)).findFirst().orElse(null);
        if (route == null) {
            route = new Route(template, template.substring(1).split("/", -1), new LinkedHashMap<>());
            routes.add(route);
        }
        if (route.handlers().putIfAbsent(method, new Endpoint(handler, access)) != null) {
            throw new IllegalArgumentException("Duplicate route " + method + " " + template);
        }
        return this;
    }

    public Match match(String method, String rawPath) {
        if (!KNOWN_METHODS.contains(method)) {
            return new NotImplemented(UNMATCHED);
        }
        if (rawPath == null || !rawPath.startsWith("/")) {
            return new NotFound(UNMATCHED);
        }
        String[] parts = rawPath.substring(1).split("/", -1);
        for (Route route : routes) {
            Map<String, String> params = bind(route.segments(), parts);
            if (params == null) {
                continue;
            }
            if (method.equals("OPTIONS")) {
                return new Options(route.template(), allow(route));
            }
            Endpoint endpoint = route.handlers().get(method);
            boolean head = method.equals("HEAD");
            if (endpoint == null && head) {
                // HEAD inherits GET's handler and, with it, GET's access level.
                endpoint = route.handlers().get("GET");
            }
            if (endpoint == null) {
                return new MethodNotAllowed(route.template(), allow(route));
            }
            return new Found(route.template(), endpoint.handler(), endpoint.access(), params, head);
        }
        return new NotFound(UNMATCHED);
    }

    private static String allow(Route route) {
        Set<String> methods = new TreeSet<>(route.handlers().keySet());
        if (methods.contains("GET")) {
            methods.add("HEAD");
        }
        methods.add("OPTIONS");
        return String.join(", ", methods);
    }

    private static Map<String, String> bind(String[] template, String[] parts) {
        if (template.length != parts.length) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < template.length; i++) {
            String t = template[i];
            if (t.startsWith("{") && t.endsWith("}")) {
                String value = percentDecode(parts[i]);
                if (value == null || value.isEmpty()) {
                    return null;
                }
                params.put(t.substring(1, t.length() - 1), value);
            } else if (!t.equals(parts[i])) {
                return null;
            }
        }
        return params;
    }

    /** RFC 3986 percent-decoding of one path segment; null when malformed or not UTF-8. */
    static String percentDecode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c != '%') {
                if (c > 0x7F) {
                    return null;
                }
                bytes.write(c);
                continue;
            }
            if (i + 2 >= segment.length()) {
                return null;
            }
            int hi = Character.digit(segment.charAt(i + 1), 16);
            int lo = Character.digit(segment.charAt(i + 2), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            bytes.write((hi << 4) | lo);
            i += 2;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
