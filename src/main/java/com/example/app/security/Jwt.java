package com.example.app.security;

import com.example.app.json.Json;
import com.example.app.json.JsonException;
import com.example.app.security.InvalidTokenException.Reason;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A decoded — but <em>not yet verified</em> — compact JWS (RFC 7515 section 3.1). Nothing this
 * class returns may be trusted until {@link JwtVerifier} has checked the signature.
 *
 * <p>Parsing reuses {@link Json}, whose strictness is exactly right here: a token carrying two
 * {@code exp} members is an attack, not a quirk, and is rejected before anyone has to decide which
 * one wins.
 */
public record Jwt(
        Map<String, Object> header,
        Map<String, Object> claims,
        byte[] signingInput,
        byte[] signature) {

    private static final Base64.Decoder URL = Base64.getUrlDecoder();

    public static Jwt decode(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new InvalidTokenException(Reason.MALFORMED);
        }
        Map<String, Object> header = parseObject(parts[0]);
        Map<String, Object> claims = parseObject(parts[1]);
        byte[] signingInput = (parts[0] + '.' + parts[1]).getBytes(StandardCharsets.US_ASCII);
        return new Jwt(header, claims, signingInput, decodeSegment(parts[2]));
    }

    public Optional<String> headerString(String name) {
        return header.get(name) instanceof String value ? Optional.of(value) : Optional.empty();
    }

    public Optional<String> claimString(String name) {
        return claims.get(name) instanceof String value ? Optional.of(value) : Optional.empty();
    }

    /**
     * A NumericDate claim (RFC 7519 section 2): seconds since the epoch, possibly fractional.
     * A present-but-unusable value is a malformed token, not an absent claim.
     */
    public Optional<Instant> claimInstant(String name) {
        Object value = claims.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof BigDecimal number)) {
            throw new InvalidTokenException(Reason.MALFORMED);
        }
        try {
            // toBigInteger() drops the fraction a NumericDate is allowed to carry;
            // longValueExact() then refuses to silently truncate an out-of-range value into a
            // plausible-looking one. Without the exact conversion, an absurd "exp" wraps into an
            // arbitrary instant instead of being rejected.
            return Optional.of(Instant.ofEpochSecond(number.toBigInteger().longValueExact()));
        } catch (ArithmeticException | DateTimeException e) {
            throw new InvalidTokenException(Reason.MALFORMED);
        }
    }

    /** {@code aud} is a string or an array of strings (RFC 7519 section 4.1.3). */
    public List<String> audiences() {
        return switch (claims.get("aud")) {
            case String single -> List.of(single);
            case List<?> many -> many.stream().filter(String.class::isInstance).map(String.class::cast).toList();
            case null, default -> List.of();
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String segment) {
        String text = new String(decodeSegment(segment), StandardCharsets.UTF_8);
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (JsonException e) {
            throw new InvalidTokenException(Reason.MALFORMED);
        }
        if (!(parsed instanceof Map<?, ?> object)) {
            throw new InvalidTokenException(Reason.MALFORMED);
        }
        return (Map<String, Object>) object;
    }

    private static byte[] decodeSegment(String segment) {
        try {
            return URL.decode(segment);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException(Reason.MALFORMED);
        }
    }
}
