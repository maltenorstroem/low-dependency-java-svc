package com.example.app.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Translates text into International Code of Signals flags: A–Z (either case) to the letter flags,
 * 0–9 to the numeral pennants, and a space to a space.
 *
 * <p>The table is {@code nautical-flags.tsv} on the classpath, read once at startup. A missing or
 * incomplete table fails startup rather than the first request that needs the missing flag.
 */
@Component
public class NauticalFlags {

    static final String RESOURCE = "/nautical-flags.tsv";
    static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static final NauticalFlag SPACE = new NauticalFlag(" ", " ", " ");

    /** Codeword and glyph by upper-case character; {@code character} is filled in per request. */
    private final Map<Character, NauticalFlag> flags;

    public NauticalFlags() {
        this.flags = load();
    }

    /**
     * One flag per character of {@code text}, in order.
     *
     * @throws IllegalArgumentException if a character has no flag
     */
    public List<NauticalFlag> translate(String text) {
        List<NauticalFlag> translated = new ArrayList<>(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                translated.add(SPACE);
                continue;
            }
            // Only ASCII letters reach the table, so upper-casing needs no locale.
            NauticalFlag flag = c < 128 ? flags.get(Character.toUpperCase(c)) : null;
            if (flag == null) {
                throw new IllegalArgumentException("No flag for character at index " + i);
            }
            translated.add(new NauticalFlag(String.valueOf(c), flag.flag(), flag.glyph()));
        }
        return translated;
    }

    private static Map<Character, NauticalFlag> load() {
        InputStream in = NauticalFlags.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException(RESOURCE + " is missing from the classpath");
        }
        Map<Character, NauticalFlag> table = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\t", -1);
                if (columns.length != 3 || columns[0].length() != 1 || ALPHABET.indexOf(columns[0].charAt(0)) < 0
                        || columns[1].isBlank() || columns[2].isBlank()) {
                    throw new IllegalStateException(RESOURCE + " line " + number + " is malformed");
                }
                char c = columns[0].charAt(0);
                if (table.put(c, new NauticalFlag(columns[0], columns[1], columns[2])) != null) {
                    throw new IllegalStateException(RESOURCE + " defines " + c + " twice");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (char c : ALPHABET.toCharArray()) {
            if (!table.containsKey(c)) {
                throw new IllegalStateException(RESOURCE + " has no flag for " + c);
            }
        }
        return Map.copyOf(table);
    }
}
