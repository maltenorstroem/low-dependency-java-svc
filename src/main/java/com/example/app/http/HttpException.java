package com.example.app.http;

import java.util.LinkedHashMap;
import java.util.Map;

/** An HTTP-level failure with a status code and a client-safe message. */
public final class HttpException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String type;
    private final transient Map<String, String> headers = new LinkedHashMap<>();

    public HttpException(int status, String detail) {
        this(status, Problems.BLANK, detail);
    }

    public HttpException(int status, String type, String detail) {
        super(detail, null, false, false);
        this.status = status;
        this.type = type;
    }

    public HttpException withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public int status() {
        return status;
    }

    public String type() {
        return type;
    }

    public Map<String, String> headers() {
        return Map.copyOf(headers);
    }
}
