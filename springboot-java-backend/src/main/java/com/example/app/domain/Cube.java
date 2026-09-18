package com.example.app.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable aggregate. {@code version} starts at 1 and increases by one on every change. */
public record Cube(UUID id, String description, CubeDefinition cube, long version, Instant createdAt,
        Instant updatedAt) {

    public Cube {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(cube, "cube");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }

    public static Cube create(UUID id, CubeInput input, Instant now) {
        return new Cube(id, input.description(), input.cube(), 1, now, now);
    }

    public Cube apply(CubeInput input, Instant now) {
        Instant updated = now.isBefore(createdAt) ? createdAt : now; // never go back in time
        return new Cube(id, input.description(), input.cube(), Math.addExact(version, 1), createdAt, updated);
    }

    /**
     * Derived, never stored: the contract marks {@code display_name} read-only, so it is a pure
     * function of the geometry and cannot drift out of sync with it.
     */
    public String displayName() {
        String noun = cube.material() == Material.MATERIALS_UNSPECIFIED
                ? "Cube"
                : cube.material().label() + " cube";
        return noun + " " + number(cube.length()) + " x " + number(cube.breadth()) + " x " + number(cube.height());
    }

    /** Drops the trailing {@code .0} that {@code Double.toString} adds to whole numbers. */
    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
