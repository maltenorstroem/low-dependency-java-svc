package com.example.app.observability;

import com.example.app.json.Json;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Structured logging to stdout, one JSON object per line (12-factor, factor XI).
 *
 * <p>This is a {@link System.Logger}, the JDK's own logging API (JEP 264). Through {@link
 * JsonLoggerFinder} the JDK itself (e.g. the HTTP server) logs through this class too, so there is
 * exactly one log format and no {@code java.util.logging} configuration to get wrong.
 *
 * <p>Logging never throws: a logging failure must not turn into a request failure.
 */
public final class Log implements System.Logger {

    private static final Object[] NO_FIELDS = {};
    private static final ReentrantLock LOCK = new ReentrantLock(); // not synchronized: avoids pinning virtual threads
    private static final ThreadLocal<Map<String, String>> CONTEXT = new ThreadLocal<>();
    private static volatile int threshold = Level.INFO.getSeverity();
    private static volatile PrintStream sink = System.out;

    private final String name;

    private Log(String name) {
        this.name = Objects.requireNonNull(name);
    }

    public static Log get(Class<?> type) {
        return new Log(type.getName());
    }

    public static Log named(String name) {
        return new Log(name);
    }

    public static void setThreshold(Level level) {
        threshold = level.getSeverity();
    }

    /** Redirects output; intended for tests. */
    public static void setSink(PrintStream stream) {
        sink = Objects.requireNonNull(stream);
    }

    /**
     * Binds fields (request id, trace id) to every log line written by the current thread until the
     * returned scope is closed. Each request runs on its own virtual thread, so this is safe.
     */
    public static Scope bind(Map<String, String> fields) {
        Map<String, String> previous = CONTEXT.get();
        CONTEXT.set(Map.copyOf(fields));
        return () -> {
            if (previous == null) {
                CONTEXT.remove();
            } else {
                CONTEXT.set(previous);
            }
        };
    }

    /** A closeable that does not throw checked exceptions. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    // ---- structured convenience API: event name + alternating key/value pairs ----

    public void debug(String event, Object... keyValues) {
        write(Level.DEBUG, event, null, keyValues);
    }

    public void info(String event, Object... keyValues) {
        write(Level.INFO, event, null, keyValues);
    }

    public void warn(String event, Object... keyValues) {
        write(Level.WARNING, event, null, keyValues);
    }

    public void error(String event, Throwable error, Object... keyValues) {
        write(Level.ERROR, event, error, keyValues);
    }

    // ---- System.Logger ----

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isLoggable(Level level) {
        return level != Level.OFF && level.getSeverity() >= threshold;
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
        write(level, localize(bundle, message), thrown, NO_FIELDS);
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String format, Object... params) {
        if (!isLoggable(level)) {
            return;
        }
        String message = localize(bundle, format);
        if (params != null && params.length > 0 && message != null) {
            try {
                message = MessageFormat.format(message, params);
            } catch (IllegalArgumentException ignored) {
                // keep the unformatted message
            }
        }
        write(level, message, null, NO_FIELDS);
    }

    private static String localize(ResourceBundle bundle, String key) {
        if (bundle == null || key == null) {
            return key;
        }
        try {
            return bundle.getString(key);
        } catch (MissingResourceException | ClassCastException e) {
            return key;
        }
    }

    private void write(Level level, String event, Throwable error, Object[] keyValues) {
        if (!isLoggable(level)) {
            return;
        }
        String line;
        try {
            line = format(level, event, error, keyValues);
        } catch (RuntimeException e) {
            line = "{\"level\":\"ERROR\",\"event\":\"log.format_failed\",\"logger\":" + Json.write(name) + "}";
        }
        LOCK.lock();
        try {
            sink.println(line);
        } catch (RuntimeException ignored) {
            // nowhere left to report this
        } finally {
            LOCK.unlock();
        }
    }

    private String format(Level level, String event, Throwable error, Object[] keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", Instant.now().toString());
        out.put("level", level.getName());
        out.put("logger", name);
        out.put("event", String.valueOf(event));
        Map<String, String> context = CONTEXT.get();
        if (context != null) {
            out.putAll(context);
        }
        if (keyValues != null) {
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                out.put(String.valueOf(keyValues[i]), safe(keyValues[i + 1]));
            }
        }
        if (error != null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("type", error.getClass().getName());
            err.put("message", error.getMessage());
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            err.put("stack", trace.toString());
            out.put("error", err);
        }
        return Json.write(out);
    }

    private static Object safe(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Double d && !Double.isFinite(d)) {
            return d.toString();
        }
        if (value instanceof Float f && !Float.isFinite(f)) {
            return f.toString();
        }
        if (value instanceof Number) {
            return value;
        }
        return String.valueOf(value);
    }
}
