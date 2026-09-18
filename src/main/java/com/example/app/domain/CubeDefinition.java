package com.example.app.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The geometry and appearance of a cube, matching {@code lgt.polaris.cube.v1.CubeDefinition}.
 * Every component has the default the contract declares, so a client may send none of them.
 */
public record CubeDefinition(double length, double breadth, double height, Colour colour, Material material) {

    public static final double DEFAULT_EDGE = 100;
    public static final double MAX_EDGE = 1_000_000;

    public static final CubeDefinition DEFAULT = new CubeDefinition(
            DEFAULT_EDGE, DEFAULT_EDGE, DEFAULT_EDGE, Colour.DEFAULT, Material.MATERIALS_UNSPECIFIED);

    public CubeDefinition {
        Objects.requireNonNull(colour, "colour");
        Objects.requireNonNull(material, "material");
        List<ValidationException.Violation> violations = new ArrayList<>();
        edge(violations, "/length", length);
        edge(violations, "/breadth", breadth);
        edge(violations, "/height", height);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }

    /** The volume, used by the generated document. */
    public double volume() {
        return length * breadth * height;
    }

    private static void edge(List<ValidationException.Violation> violations, String pointer, double value) {
        if (!Double.isFinite(value) || value <= 0 || value > MAX_EDGE) {
            violations.add(new ValidationException.Violation(
                    pointer, "must be a number greater than 0 and at most " + (long) MAX_EDGE));
        }
    }
}
