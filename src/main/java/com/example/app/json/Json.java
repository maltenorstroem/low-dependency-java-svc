package com.example.app.json;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A small, strict JSON implementation (RFC 8259, with the I-JSON restrictions of RFC 7493).
 *
 * <p>Parsing yields plain, immutable Java values: {@code Map<String, Object>}, {@code
 * List<Object>}, {@code String}, {@link BigDecimal}, {@code Boolean} and {@code null}. There is
 * no reflection-based data binding, which removes the whole class of deserialization
 * vulnerabilities that JSON libraries keep shipping CVEs for.
 *
 * <p>Defensive rules: bounded nesting depth, bounded number length, duplicate keys rejected,
 * unpaired surrogates rejected, no trailing content, no comments, no NaN/Infinity.
 */
public final class Json {

    public static final int MAX_DEPTH = 64;
    public static final int MAX_NUMBER_LENGTH = 64;

    private Json() {}

    public static Object parse(String text) {
        return new Parser(text).document();
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.toString();
    }

    // ------------------------------------------------------------------ parser

    private static final class Parser {
        private final String s;
        private int pos;
        private int depth;

        Parser(String s) {
            this.s = s;
        }

        Object document() {
            skipWhitespace();
            Object value = value();
            skipWhitespace();
            if (pos != s.length()) {
                throw error("Unexpected content after JSON value");
            }
            return value;
        }

        private Object value() {
            int c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                case -1 -> throw error("Unexpected end of input");
                default -> {
                    if (c == '-' || isDigit(c)) {
                        yield number();
                    }
                    throw error("Unexpected character");
                }
            };
        }

        private Map<String, Object> object() {
            enter();
            pos++; // {
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                depth--;
                return Collections.unmodifiableMap(map);
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("Expected a string key");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = value();
                if (map.containsKey(key)) {
                    throw error("Duplicate object key");
                }
                map.put(key, value);
                skipWhitespace();
                int c = next();
                if (c == '}') {
                    break;
                }
                if (c != ',') {
                    throw error("Expected ',' or '}'");
                }
            }
            depth--;
            return Collections.unmodifiableMap(map);
        }

        private List<Object> array() {
            enter();
            pos++; // [
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                depth--;
                return Collections.unmodifiableList(list);
            }
            while (true) {
                skipWhitespace();
                list.add(value());
                skipWhitespace();
                int c = next();
                if (c == ']') {
                    break;
                }
                if (c != ',') {
                    throw error("Expected ',' or ']'");
                }
            }
            depth--;
            return Collections.unmodifiableList(list);
        }

        private String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c < 0x20) {
                    throw error("Unescaped control character in string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                int e = next();
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(hex4());
                    default -> throw error("Invalid escape sequence");
                }
            }
            requireWellFormedUnicode(sb);
            return sb.toString();
        }

        private char hex4() {
            if (pos + 4 > s.length()) {
                throw error("Invalid unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(s.charAt(pos++), 16);
                if (digit < 0) {
                    throw error("Invalid unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private void requireWellFormedUnicode(CharSequence text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                        throw error("Unpaired surrogate in string");
                    }
                    i++;
                } else if (Character.isLowSurrogate(c)) {
                    throw error("Unpaired surrogate in string");
                }
            }
        }

        private BigDecimal number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (peek() == '0') {
                pos++;
            } else if (isDigit(peek())) {
                digits();
            } else {
                throw error("Invalid number");
            }
            if (peek() == '.') {
                pos++;
                if (!isDigit(peek())) {
                    throw error("Invalid number");
                }
                digits();
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                if (!isDigit(peek())) {
                    throw error("Invalid number");
                }
                digits();
            }
            if (pos - start > MAX_NUMBER_LENGTH) {
                throw error("Number too long");
            }
            try {
                return new BigDecimal(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error("Number out of range");
            }
        }

        private void digits() {
            while (isDigit(peek())) {
                pos++;
            }
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw error("Invalid literal");
            }
            pos += word.length();
            return value;
        }

        private void enter() {
            if (++depth > MAX_DEPTH) {
                throw error("Nesting too deep");
            }
        }

        private void expect(char c) {
            if (next() != c) {
                throw error("Expected '" + c + "'");
            }
        }

        private int peek() {
            return pos < s.length() ? s.charAt(pos) : -1;
        }

        private int next() {
            if (pos >= s.length()) {
                throw error("Unexpected end of input");
            }
            return s.charAt(pos++);
        }

        private void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        private static boolean isDigit(int c) {
            return c >= '0' && c <= '9';
        }

        private JsonException error(String message) {
            return new JsonException(message + " at offset " + pos);
        }
    }

    // ------------------------------------------------------------------ writer

    private static void write(StringBuilder out, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Nesting too deep");
        }
        switch (value) {
            case null -> out.append("null");
            case String s -> string(out, s);
            case Boolean b -> out.append(b.booleanValue());
            case Double d -> finite(out, d);
            case Float f -> finite(out, f.doubleValue());
            case BigDecimal d -> out.append(d.toString());
            case Number n -> out.append(n.toString()); // Integer, Long, Short, Byte, BigInteger
            case Instant i -> string(out, i.toString()); // RFC 3339 in UTC
            case UUID u -> string(out, u.toString());
            case Enum<?> e -> string(out, e.name());
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("JSON object keys must be strings");
                    }
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    string(out, key);
                    out.append(':');
                    write(out, entry.getValue(), depth + 1);
                }
                out.append('}');
            }
            case Iterable<?> items -> {
                out.append('[');
                boolean first = true;
                for (Object item : items) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(out, item, depth + 1);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Unsupported JSON type: " + value.getClass().getName());
        }
    }

    private static void finite(StringBuilder out, double d) {
        if (!Double.isFinite(d)) {
            throw new IllegalArgumentException("NaN and Infinity are not valid JSON");
        }
        out.append(d);
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    // Control chars must be escaped; U+2028/U+2029 are escaped for JavaScript safety.
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
