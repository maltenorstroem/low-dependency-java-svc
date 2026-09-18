package com.example.app.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Material a cube is made of. The constant names are the proto3 JSON representation of
 * {@code lgt.polaris.cube.v1.Materials}, so {@code Json.write} serializes them verbatim.
 */
public enum Material {
    MATERIALS_UNSPECIFIED,
    MATERIALS_GLASS,
    MATERIALS_WOOD,
    MATERIALS_CERAMICS,
    MATERIALS_METALS,
    MATERIALS_PLASTICS,
    MATERIALS_TEXTILES,
    MATERIALS_LEATHER,
    MATERIALS_PAPER,
    MATERIALS_RUBBER;

    /** Empty rather than throwing, so callers can report an unknown value as a violation. */
    public static Optional<Material> parse(String name) {
        for (Material material : values()) {
            if (material.name().equals(name)) {
                return Optional.of(material);
            }
        }
        return Optional.empty();
    }

    /** Human-readable form for display names: {@code MATERIALS_GLASS} becomes {@code Glass}. */
    public String label() {
        String bare = name().substring("MATERIALS_".length());
        return bare.charAt(0) + bare.substring(1).toLowerCase(Locale.ROOT);
    }
}
