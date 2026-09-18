package com.example.app.http;

import java.util.Locale;
import java.util.Set;

/** Content negotiation helpers (RFC 9110 section 12). Deliberately strict. */
final class MediaTypes {

    private static final Set<String> JSON_RANGES =
            Set.of("*/*", "application/*", "application/json", "application/problem+json");

    private MediaTypes() {}

    /** {@code application/json}, optionally with {@code charset=utf-8} (RFC 8259 mandates UTF-8). */
    static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String[] parts = contentType.split(";");
        if (!parts[0].strip().toLowerCase(Locale.ROOT).equals("application/json")) {
            return false;
        }
        for (int i = 1; i < parts.length; i++) {
            String[] param = parts[i].split("=", 2);
            if (param.length != 2) {
                return false;
            }
            String name = param[0].strip().toLowerCase(Locale.ROOT);
            String value = unquote(param[1].strip()).toLowerCase(Locale.ROOT);
            if (name.equals("charset") && !value.equals("utf-8")) {
                return false;
            }
        }
        return true;
    }

    /** True when an {@code Accept} header admits a JSON response. A missing header accepts anything. */
    static boolean acceptsJson(String accept) {
        return accepts(accept, JSON_RANGES);
    }

    /**
     * True when an {@code Accept} header admits {@code type}. A missing header accepts anything.
     * The ranges considered are {@code type} itself plus its type wildcard and {@code *}/{@code *}.
     */
    static boolean accepts(String accept, String type) {
        String bare = type.toLowerCase(Locale.ROOT);
        String wildcard = bare.substring(0, bare.indexOf('/') + 1) + "*";
        return accepts(accept, Set.of("*/*", wildcard, bare));
    }

    private static boolean accepts(String accept, Set<String> ranges) {
        if (accept == null || accept.isBlank()) {
            return true;
        }
        for (String range : accept.split(",")) {
            String[] parts = range.split(";");
            String type = parts[0].strip().toLowerCase(Locale.ROOT);
            if (ranges.contains(type) && quality(parts) > 0) {
                return true;
            }
        }
        return false;
    }

    private static double quality(String[] parts) {
        for (int i = 1; i < parts.length; i++) {
            String[] param = parts[i].split("=", 2);
            if (param.length == 2 && param[0].strip().equalsIgnoreCase("q")) {
                try {
                    return Double.parseDouble(param[1].strip());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 1;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }
}
