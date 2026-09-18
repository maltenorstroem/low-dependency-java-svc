package com.example.app.json;

/** The input is not valid JSON. Messages never echo input content. */
public final class JsonException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }
}
