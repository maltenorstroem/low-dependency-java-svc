package com.example.app.json;

import static com.example.app.testing.Assert.assertEquals;
import static com.example.app.testing.Assert.assertThrows;

import com.example.app.testing.Test;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonTest {

    @Test
    void parsesAllValueTypes() {
        Object value = Json.parse(" {\"s\":\"a\\u00e9\\n\",\"n\":-1.5e3,\"t\":true,\"f\":false,\"z\":null,\"a\":[1,[]],\"o\":{}} ");
        Map<?, ?> map = (Map<?, ?>) value;
        assertEquals("a\u00e9\n", map.get("s"));
        assertEquals(new BigDecimal("-1.5e3"), map.get("n"));
        assertEquals(Boolean.TRUE, map.get("t"));
        assertEquals(Boolean.FALSE, map.get("f"));
        assertEquals(true, map.containsKey("z") && map.get("z") == null);
        assertEquals(List.of(new BigDecimal("1"), List.of()), map.get("a"));
        assertEquals(Map.of(), map.get("o"));
    }

    @Test
    void acceptsEscapedSurrogatePairs() {
        assertEquals("\uD83D\uDE00", Json.parse("\"\\uD83D\\uDE00\""));
    }

    @Test
    void rejectsInvalidDocuments() {
        for (String bad : List.of(
                "", " ", "{", "}", "[1,]", "{\"a\":1,}", "{\"a\":1,\"a\":2}", "01", "1.", ".5", "+1", "-",
                "NaN", "Infinity", "'a'", "\"abc", "\"\\x\"", "\"\\u12\"", "\"a\u0001\"", "tru", "nul",
                "1 2", "{} {}", "// c\n1", "\"\\uD800\"", "\"\\uDC00\"", "\uFEFF{}", "[1 2]", "{\"a\" 1}",
                "1" + "0".repeat(Json.MAX_NUMBER_LENGTH))) {
            assertThrows(JsonException.class, () -> Json.parse(bad));
        }
    }

    @Test
    void limitsNestingDepth() {
        String ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
        Json.parse(ok);
        String tooDeep = "[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1);
        assertThrows(JsonException.class, () -> Json.parse(tooDeep));
        // A huge unbalanced input must fail fast rather than overflow the stack.
        String hostile = "[".repeat(100_000);
        assertThrows(JsonException.class, () -> Json.parse(hostile));
    }

    @Test
    void writesAndEscapes() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("q", "\"\\/\n\u0000\u2028");
        map.put("n", 42);
        map.put("d", 1.5);
        map.put("b", true);
        map.put("z", null);
        map.put("l", Arrays.asList(1, "x"));
        String json = Json.write(map);
        assertEquals("{\"q\":\"\\\"\\\\/\\n\\u0000\\u2028\",\"n\":42,\"d\":1.5,\"b\":true,\"z\":null,\"l\":[1,\"x\"]}", json);
        assertEquals(map.get("q"), ((Map<?, ?>) Json.parse(json)).get("q"));
    }

    @Test
    void writerRejectsNonFiniteNumbersAndUnknownTypes() {
        assertThrows(IllegalArgumentException.class, () -> Json.write(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Map.of(1, 2)));
    }
}
