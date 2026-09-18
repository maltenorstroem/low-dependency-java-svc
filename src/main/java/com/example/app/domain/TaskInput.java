package com.example.app.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Validated, normalized client input. It is impossible to construct an invalid instance, so
 * nothing downstream has to re-check.
 */
public record TaskInput(String title, boolean completed) {

    public static final int MAX_TITLE_LENGTH = 200;

    public TaskInput {
        List<ValidationException.Violation> violations = new ArrayList<>();
        if (title == null) {
            violations.add(new ValidationException.Violation("/title", "is required"));
        } else {
            title = Normalizer.normalize(title.strip(), Normalizer.Form.NFC);
            if (title.isEmpty()) {
                violations.add(new ValidationException.Violation("/title", "must not be blank"));
            } else if (title.codePointCount(0, title.length()) > MAX_TITLE_LENGTH) {
                violations.add(new ValidationException.Violation(
                        "/title", "must be at most " + MAX_TITLE_LENGTH + " characters"));
            } else if (title.chars().anyMatch(Character::isISOControl)) {
                violations.add(new ValidationException.Violation("/title", "must not contain control characters"));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }
}
