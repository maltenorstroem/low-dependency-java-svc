package com.example.app.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One character of the caller's text as a signal flag: the character as sent, the flag's ICS
 * codeword, and an approximation of the flag in coloured squares. A space is a space in all three.
 */
public record NauticalFlag(@JsonProperty("char") String character, String flag, String glyph) {
}
