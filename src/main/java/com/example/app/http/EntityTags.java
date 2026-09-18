package com.example.app.http;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * Conditional requests (RFC 9110 section 13) for resources whose version is a counter. The entity
 * tag of version {@code n} is the strong tag {@code "n"}.
 */
public final class EntityTags {

    private record Tag(boolean weak, String opaque) {}

    private EntityTags() {}

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * Evaluates a mandatory {@code If-Match} (lost-update prevention). Missing header: 428. The
     * returned predicate tests the current version with strong comparison; {@code *} matches any.
     */
    public static LongPredicate requireIfMatch(Request request) {
        String header = request.header("If-Match").orElseThrow(() -> new HttpException(
                428, Problems.PRECONDITION_REQUIRED,
                "If-Match is required; send the ETag from a previous GET"));
        if (header.strip().equals("*")) {
            return version -> true;
        }
        List<String> strong = new ArrayList<>();
        for (Tag tag : parse(header)) {
            if (!tag.weak()) {
                strong.add(tag.opaque());
            }
        }
        return version -> strong.contains(Long.toString(version));
    }

    /** {@code If-None-Match} with weak comparison: true when the client already has this version. */
    public static boolean ifNoneMatchHits(Request request, long version) {
        String header = request.header("If-None-Match").orElse(null);
        if (header == null) {
            return false;
        }
        if (header.strip().equals("*")) {
            return true;
        }
        String current = Long.toString(version);
        return parse(header).stream().anyMatch(tag -> tag.opaque().equals(current));
    }

    private static List<Tag> parse(String header) {
        List<Tag> tags = new ArrayList<>();
        int i = 0;
        int n = header.length();
        while (i < n) {
            char c = header.charAt(i);
            if (c == ' ' || c == '\t' || c == ',') {
                i++;
                continue;
            }
            boolean weak = false;
            if (header.startsWith("W/", i)) {
                weak = true;
                i += 2;
            }
            if (i >= n || header.charAt(i) != '"') {
                throw new HttpException(400, "Malformed entity tag list");
            }
            int end = header.indexOf('"', i + 1);
            if (end < 0) {
                throw new HttpException(400, "Malformed entity tag list");
            }
            tags.add(new Tag(weak, header.substring(i + 1, end)));
            i = end + 1;
        }
        return tags;
    }
}
