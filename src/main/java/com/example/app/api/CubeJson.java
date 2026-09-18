package com.example.app.api;

import com.example.app.domain.Colour;
import com.example.app.domain.Cube;
import com.example.app.domain.CubeDefinition;
import com.example.app.domain.CubeInput;
import com.example.app.domain.Material;
import com.example.app.domain.ValidationException;
import com.example.app.domain.ValidationException.Violation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Explicit mapping between JSON and the domain, following the {@code lgt.polaris.cube} contract.
 * No reflection, no annotations, no surprises.
 *
 * <p>Unlike {@link TaskJson} the body is nested, so violations carry a full JSON Pointer
 * (RFC 6901) such as {@code /cube/colour/red} and the whole tree is checked in one pass.
 */
final class CubeJson {

    private static final Set<String> WRITABLE = Set.of("description", "cube");
    /** Accepted and ignored, so clients can PUT back what they got from GET. */
    private static final Set<String> READ_ONLY = Set.of("id", "displayName", "version", "createdAt", "updatedAt");
    private static final Set<String> CUBE_FIELDS = Set.of("length", "breadth", "height", "colour", "material");
    private static final Set<String> COLOUR_FIELDS = Set.of("red", "green", "blue", "alpha");
    private static final int MAX_REPORTED_VIOLATIONS = 20;

    private CubeJson() {}

    static Map<String, Object> toJson(Cube cube) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", cube.id());
        json.put("displayName", cube.displayName());
        json.put("description", cube.description());
        json.put("cube", definitionToJson(cube.cube()));
        json.put("version", cube.version());
        json.put("createdAt", cube.createdAt());
        json.put("updatedAt", cube.updatedAt());
        return json;
    }

    private static Map<String, Object> definitionToJson(CubeDefinition definition) {
        Map<String, Object> colour = new LinkedHashMap<>();
        colour.put("red", definition.colour().red());
        colour.put("green", definition.colour().green());
        colour.put("blue", definition.colour().blue());
        colour.put("alpha", definition.colour().alpha());

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("length", definition.length());
        json.put("breadth", definition.breadth());
        json.put("height", definition.height());
        json.put("colour", colour);
        json.put("material", definition.material());
        return json;
    }

    /**
     * Checks shape, types and ranges (unknown fields are errors) and reports every problem at once.
     * Missing values fall back to the defaults the contract declares.
     */
    static CubeInput toInput(Object body) {
        List<Violation> violations = new ArrayList<>();
        Map<?, ?> map = object(body, "", violations);
        if (map == null) {
            throw new ValidationException(violations);
        }
        unknownFields(map, "", WRITABLE, READ_ONLY, violations);

        String description = string(map.get("description"), "/description", violations);
        CubeDefinition cube = definition(map.get("cube"), violations);

        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return new CubeInput(description, cube);
    }

    private static CubeDefinition definition(Object raw, List<Violation> violations) {
        if (raw == null) {
            return CubeDefinition.DEFAULT;
        }
        Map<?, ?> map = object(raw, "/cube", violations);
        if (map == null) {
            return CubeDefinition.DEFAULT;
        }
        unknownFields(map, "/cube", CUBE_FIELDS, Set.of(), violations);

        double length = edge(map.get("length"), "/cube/length", violations);
        double breadth = edge(map.get("breadth"), "/cube/breadth", violations);
        double height = edge(map.get("height"), "/cube/height", violations);
        Colour colour = colour(map.get("colour"), violations);
        Material material = material(map.get("material"), violations);
        return violations.isEmpty()
                ? new CubeDefinition(length, breadth, height, colour, material)
                : CubeDefinition.DEFAULT; // never used: the caller throws
    }

    private static Colour colour(Object raw, List<Violation> violations) {
        if (raw == null) {
            return Colour.DEFAULT;
        }
        Map<?, ?> map = object(raw, "/cube/colour", violations);
        if (map == null) {
            return Colour.DEFAULT;
        }
        unknownFields(map, "/cube/colour", COLOUR_FIELDS, Set.of(), violations);

        int red = channel(map.get("red"), "/cube/colour/red", violations);
        int green = channel(map.get("green"), "/cube/colour/green", violations);
        int blue = channel(map.get("blue"), "/cube/colour/blue", violations);
        double alpha = alpha(map.get("alpha"), violations);
        return violations.isEmpty() ? new Colour(red, green, blue, alpha) : Colour.DEFAULT;
    }

    // ---------------------------------------------------------------- scalar readers

    private static Map<?, ?> object(Object raw, String pointer, List<Violation> violations) {
        if (raw instanceof Map<?, ?> map) {
            return map;
        }
        add(violations, new Violation(pointer, "must be a JSON object"));
        return null;
    }

    private static String string(Object raw, String pointer, List<Violation> violations) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof String text) {
            return text;
        }
        add(violations, new Violation(pointer, "must be a string"));
        return "";
    }

    private static double edge(Object raw, String pointer, List<Violation> violations) {
        if (raw == null) {
            return CubeDefinition.DEFAULT_EDGE;
        }
        BigDecimal value = number(raw, pointer, violations);
        if (value == null) {
            return CubeDefinition.DEFAULT_EDGE;
        }
        double edge = value.doubleValue();
        if (!Double.isFinite(edge) || edge <= 0 || edge > CubeDefinition.MAX_EDGE) {
            add(violations, new Violation(
                    pointer, "must be a number greater than 0 and at most " + (long) CubeDefinition.MAX_EDGE));
            return CubeDefinition.DEFAULT_EDGE;
        }
        return edge;
    }

    private static int channel(Object raw, String pointer, List<Violation> violations) {
        if (raw == null) {
            return Colour.MAX_CHANNEL;
        }
        BigDecimal value = number(raw, pointer, violations);
        if (value == null) {
            return Colour.MAX_CHANNEL;
        }
        if (value.stripTrailingZeros().scale() > 0) {
            add(violations, new Violation(pointer, "must be an integer"));
            return Colour.MAX_CHANNEL;
        }
        if (value.compareTo(BigDecimal.valueOf(Colour.MIN_CHANNEL)) < 0
                || value.compareTo(BigDecimal.valueOf(Colour.MAX_CHANNEL)) > 0) {
            add(violations, new Violation(
                    pointer, "must be between " + Colour.MIN_CHANNEL + " and " + Colour.MAX_CHANNEL));
            return Colour.MAX_CHANNEL;
        }
        return value.intValue();
    }

    private static double alpha(Object raw, List<Violation> violations) {
        if (raw == null) {
            return 1;
        }
        BigDecimal value = number(raw, "/cube/colour/alpha", violations);
        if (value == null) {
            return 1;
        }
        double alpha = value.doubleValue();
        if (!Double.isFinite(alpha) || alpha < 0 || alpha > 1) {
            add(violations, new Violation("/cube/colour/alpha", "must be between 0 and 1"));
            return 1;
        }
        return alpha;
    }

    private static Material material(Object raw, List<Violation> violations) {
        if (raw == null) {
            return Material.MATERIALS_UNSPECIFIED;
        }
        if (!(raw instanceof String name)) {
            add(violations, new Violation("/cube/material", "must be a string"));
            return Material.MATERIALS_UNSPECIFIED;
        }
        Optional<Material> material = Material.parse(name);
        if (material.isEmpty()) {
            add(violations, new Violation("/cube/material", "is not a known material"));
            return Material.MATERIALS_UNSPECIFIED;
        }
        return material.get();
    }

    /** {@link com.example.app.json.Json} yields every number as a {@link BigDecimal}. */
    private static BigDecimal number(Object raw, String pointer, List<Violation> violations) {
        if (raw instanceof BigDecimal value) {
            return value;
        }
        add(violations, new Violation(pointer, "must be a number"));
        return null;
    }

    // ---------------------------------------------------------------- shared helpers

    private static void unknownFields(
            Map<?, ?> map, String prefix, Set<String> writable, Set<String> readOnly, List<Violation> violations) {
        for (Object key : map.keySet()) {
            String name = (String) key;
            if (!writable.contains(name) && !readOnly.contains(name)) {
                add(violations, new Violation(prefix + pointer(name), "is not a known field"));
            }
        }
    }

    private static void add(List<Violation> violations, Violation violation) {
        if (violations.size() < MAX_REPORTED_VIOLATIONS) {
            violations.add(violation);
        }
    }

    /** JSON Pointer (RFC 6901) segment for a member name; long names are truncated. */
    private static String pointer(String name) {
        String shortened = name.length() > 64 ? name.substring(0, 64) : name;
        return "/" + shortened.replace("~", "~0").replace("/", "~1");
    }
}
