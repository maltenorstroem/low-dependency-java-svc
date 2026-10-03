package com.example.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The embedded flag table and the translation, without a server. */
class NauticalFlagsTest {

    private final NauticalFlags flags = new NauticalFlags();

    @Test
    void hasADistinctFlagForEveryLetterAndDigit() {
        List<NauticalFlag> translated = flags.translate(NauticalFlags.ALPHABET);
        assertEquals(36, translated.size());
        Set<String> codewords = new HashSet<>();
        for (NauticalFlag flag : translated) {
            assertFalse(flag.glyph().isBlank(), flag.flag());
            codewords.add(flag.flag());
        }
        assertEquals(36, codewords.size());
        assertEquals("Zulu", translated.get(25).flag());
        assertEquals("Novenine", translated.get(35).flag());
    }

    @Test
    void readsLettersInEitherCaseAndEchoesThemAsSent() {
        List<NauticalFlag> translated = flags.translate("xX");
        assertEquals(new NauticalFlag("x", "X-ray", "⬜➕⬜"), translated.get(0));
        assertEquals(new NauticalFlag("X", "X-ray", "⬜➕⬜"), translated.get(1));
    }

    @Test
    void keepsSpacesAsSpaces() {
        assertEquals(List.of(new NauticalFlag(" ", " ", " ")), flags.translate(" "));
        assertEquals(" ", flags.translate("S O S").get(1).flag());
    }

    @Test
    void refusesCharactersWithoutAFlag() {
        for (String text : new String[] {"!", "ä", "\t", "a-b", "🚩"}) {
            assertThrows(IllegalArgumentException.class, () -> flags.translate(text), text);
        }
    }
}
