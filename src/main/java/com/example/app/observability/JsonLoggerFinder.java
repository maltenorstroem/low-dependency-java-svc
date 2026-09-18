package com.example.app.observability;

/**
 * Plugs {@link Log} into the JDK platform logging service (JEP 264), so JDK components log as
 * structured JSON too. Registered in {@code module-info.java} and, for class-path mode, in
 * {@code META-INF/services}.
 */
public final class JsonLoggerFinder extends System.LoggerFinder {

    public JsonLoggerFinder() {
        // required public no-arg constructor for ServiceLoader
    }

    @Override
    public System.Logger getLogger(String name, Module module) {
        return Log.named(name);
    }
}
