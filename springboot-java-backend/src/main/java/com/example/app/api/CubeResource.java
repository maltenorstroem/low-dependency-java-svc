package com.example.app.api;

import com.example.app.domain.Colour;
import com.example.app.domain.Cube;
import com.example.app.domain.CubeDefinition;
import com.example.app.domain.CubeInput;
import com.example.app.domain.Material;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;

/**
 * The JSON representation of a cube, following the {@code lgt.polaris.cube} contract.
 *
 * <p>Unlike a task the body is nested, and every component has a declared default, so each field is
 * a boxed type: absent means "use the default", not "zero". The ranges are bean-validation
 * constraints rather than checks written out by hand, because the validator reports every violation
 * in one pass and its property path maps straight onto the JSON Pointer the contract promises
 * ({@code cube.colour.red} becomes {@code /cube/colour/red}). The domain records check the same
 * ranges again, which is what makes an invalid instance impossible for any other caller.
 */
public record CubeResource(

        @JsonProperty(access = JsonProperty.Access.READ_ONLY) UUID id,

        @JsonProperty(access = JsonProperty.Access.READ_ONLY) String displayName,

        String description,

        @Valid Definition cube,

        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Long version,

        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Instant createdAt,

        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Instant updatedAt) {

    private static final String EDGE_MESSAGE = "must be a number greater than 0 and at most 1000000";
    private static final String CHANNEL_MESSAGE = "must be between 0 and 255";

    /** The geometry and appearance of the cube. */
    public record Definition(

            @DecimalMin(value = "0", inclusive = false, message = EDGE_MESSAGE)
            @DecimalMax(value = "1000000", message = EDGE_MESSAGE) Double length,

            @DecimalMin(value = "0", inclusive = false, message = EDGE_MESSAGE)
            @DecimalMax(value = "1000000", message = EDGE_MESSAGE) Double breadth,

            @DecimalMin(value = "0", inclusive = false, message = EDGE_MESSAGE)
            @DecimalMax(value = "1000000", message = EDGE_MESSAGE) Double height,

            @Valid ColourResource colour,

            Material material) {
    }

    /** An RGBA colour: channels 0..255, alpha an opacity between 0 and 1. */
    public record ColourResource(

            @Min(value = 0, message = CHANNEL_MESSAGE) @Max(value = 255, message = CHANNEL_MESSAGE) Integer red,

            @Min(value = 0, message = CHANNEL_MESSAGE) @Max(value = 255, message = CHANNEL_MESSAGE) Integer green,

            @Min(value = 0, message = CHANNEL_MESSAGE) @Max(value = 255, message = CHANNEL_MESSAGE) Integer blue,

            @DecimalMin(value = "0", message = "must be between 0 and 1")
            @DecimalMax(value = "1", message = "must be between 0 and 1") Double alpha) {
    }

    static CubeResource of(Cube cube) {
        CubeDefinition d = cube.cube();
        Colour c = d.colour();
        return new CubeResource(
                cube.id(),
                cube.displayName(),
                cube.description(),
                new Definition(d.length(), d.breadth(), d.height(),
                        new ColourResource(c.red(), c.green(), c.blue(), c.alpha()), d.material()),
                cube.version(),
                cube.createdAt(),
                cube.updatedAt());
    }

    /** Absent components fall back to the defaults the contract declares, so {@code {}} is valid. */
    CubeInput toInput() {
        return new CubeInput(description, definition());
    }

    private CubeDefinition definition() {
        if (cube == null) {
            return CubeDefinition.DEFAULT;
        }
        return new CubeDefinition(
                orDefault(cube.length()),
                orDefault(cube.breadth()),
                orDefault(cube.height()),
                colour(),
                cube.material() == null ? Material.MATERIALS_UNSPECIFIED : cube.material());
    }

    private Colour colour() {
        ColourResource c = cube.colour();
        if (c == null) {
            return Colour.DEFAULT;
        }
        return new Colour(
                c.red() == null ? Colour.MAX_CHANNEL : c.red(),
                c.green() == null ? Colour.MAX_CHANNEL : c.green(),
                c.blue() == null ? Colour.MAX_CHANNEL : c.blue(),
                c.alpha() == null ? 1 : c.alpha());
    }

    private static double orDefault(Double value) {
        return value == null ? CubeDefinition.DEFAULT_EDGE : value;
    }
}
