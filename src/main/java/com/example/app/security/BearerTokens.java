package com.example.app.security;

import com.example.app.security.InvalidTokenException.Reason;
import java.util.Locale;

/** Extraction of the credential from an {@code Authorization} header (RFC 6750 section 2.1). */
public final class BearerTokens {

    private static final String SCHEME = "bearer";

    private BearerTokens() {}

    /**
     * Returns the token, or throws. The size check happens here, before any decoding or parsing,
     * so an oversized header costs one comparison rather than a base64 decode and two JSON parses.
     */
    public static String extract(String authorization, int maxBytes) {
        if (authorization == null || authorization.isBlank()) {
            throw new MissingCredentialsException();
        }
        String header = authorization.strip();
        int space = header.indexOf(' ');
        if (space < 0 || !header.substring(0, space).toLowerCase(Locale.ROOT).equals(SCHEME)) {
            // Some other scheme (Basic, Negotiate...) is no credential as far as we are concerned.
            throw new MissingCredentialsException();
        }
        String token = header.substring(space + 1).strip();
        if (token.isEmpty()) {
            throw new MissingCredentialsException();
        }
        if (token.length() > maxBytes) {
            throw new InvalidTokenException(Reason.TOO_LARGE);
        }
        if (!isToken68(token)) {
            throw new InvalidTokenException(Reason.MALFORMED);
        }
        return token;
    }

    /**
     * The token68 syntax of RFC 9110, minus {@code =}: RFC 7515 requires base64url without
     * padding, so a padded segment is malformed rather than merely unusual.
     */
    private static boolean isToken68(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '+' || c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
