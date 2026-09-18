package com.example.app.api;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict identifier parsing. {@link UUID#fromString} alone accepts non-canonical input such as
 * "1-1-1-1-1", and Spring's own String-to-UUID conversion delegates to it, so the path variable is
 * taken as text and checked here. An unparseable id is simply not found.
 */
final class Identifiers {

    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Identifiers() {}

    static UUID parse(String raw, String notFoundMessage) {
        if (raw == null || !UUID_TEXT.matcher(raw).matches()) {
            throw new com.example.app.domain.NotFoundException(notFoundMessage);
        }
        return UUID.fromString(raw.toLowerCase(Locale.ROOT));
    }
}
