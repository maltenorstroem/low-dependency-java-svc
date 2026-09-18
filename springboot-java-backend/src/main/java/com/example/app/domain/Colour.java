package com.example.app.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * An RGBA colour. Channels are 0..255 and {@code alpha} is an opacity between 0 and 1, matching
 * {@code lgt.polaris.cube.v1.Colour}.
 *
 * <p>The API layer validates first and reports every violation at once; the checks here make an
 * invalid instance impossible for any other caller.
 */
public record Colour(int red, int green, int blue, double alpha) {

    public static final int MIN_CHANNEL = 0;
    public static final int MAX_CHANNEL = 255;

    /** Opaque white, the default of every channel in the contract. */
    public static final Colour DEFAULT = new Colour(MAX_CHANNEL, MAX_CHANNEL, MAX_CHANNEL, 1);

    public Colour {
        List<ValidationException.Violation> violations = new ArrayList<>();
        channel(violations, "/red", red);
        channel(violations, "/green", green);
        channel(violations, "/blue", blue);
        if (!Double.isFinite(alpha) || alpha < 0 || alpha > 1) {
            violations.add(new ValidationException.Violation("/alpha", "must be between 0 and 1"));
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }

    private static void channel(List<ValidationException.Violation> violations, String pointer, int value) {
        if (value < MIN_CHANNEL || value > MAX_CHANNEL) {
            violations.add(new ValidationException.Violation(
                    pointer, "must be between " + MIN_CHANNEL + " and " + MAX_CHANNEL));
        }
    }
}
