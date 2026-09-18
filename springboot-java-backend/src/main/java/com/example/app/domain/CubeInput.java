package com.example.app.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Validated, normalized client input for a cube. The contract gives every field a default, so an
 * empty request body yields a default white cube rather than an error.
 */
public record CubeInput(String description, CubeDefinition cube) {

    public static final int MAX_DESCRIPTION_LENGTH = 1_000;

    public CubeInput {
        List<ValidationException.Violation> violations = new ArrayList<>();
        description = description == null ? "" : Normalizer.normalize(description.strip(), Normalizer.Form.NFC);
        if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION_LENGTH) {
            violations.add(new ValidationException.Violation(
                    "/description", "must be at most " + MAX_DESCRIPTION_LENGTH + " characters"));
        } else if (description.chars().anyMatch(Character::isISOControl)) {
            violations.add(new ValidationException.Violation(
                    "/description", "must not contain control characters"));
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        cube = cube == null ? CubeDefinition.DEFAULT : cube;
    }
}
