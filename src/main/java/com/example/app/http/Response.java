package com.example.app.http;

import com.example.app.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** An immutable, fully buffered response. Buffering means a failure can never leave a half-written body. */
public record Response(int status, Map<String, String> headers, String contentType, byte[] body) {

    public static final String JSON = "application/json";

    public Response {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Invalid status " + status);
        }
        headers = Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
        Objects.requireNonNull(contentType, "contentType");
    }

    public static Response json(int status, Object value) {
        return new Response(status, Map.of(), JSON, Json.write(value).getBytes(StandardCharsets.UTF_8));
    }

    public static Response bytes(int status, String contentType, byte[] body) {
        return new Response(status, Map.of(), contentType, body);
    }

    public static Response empty(int status) {
        return new Response(status, Map.of(), JSON, null);
    }

    public Response withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new Response(status, copy, contentType, body);
    }
}
