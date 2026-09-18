package com.example.app.config;

/** Thrown at startup when the environment does not describe a valid configuration. */
public final class ConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }
}
