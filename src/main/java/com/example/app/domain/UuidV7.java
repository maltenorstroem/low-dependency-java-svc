package com.example.app.domain;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Comparator;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * UUID version 7 (RFC 9562): 48-bit Unix millisecond timestamp followed by random bits. Ids are
 * unguessable, globally unique and sort by creation time, which gives stable cursor pagination and
 * index-friendly database keys.
 */
public final class UuidV7 {

    /** Unsigned ordering. {@link UUID#compareTo} compares signed longs, which is wrong for v7. */
    public static final Comparator<UUID> ORDER = (a, b) -> {
        int c = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return c != 0 ? c : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    };

    private UuidV7() {}

    public static Supplier<UUID> generator(Clock clock) {
        SecureRandom random = new SecureRandom();
        return () -> create(clock.millis(), random);
    }

    static UUID create(long unixMillis, Random random) {
        long msb = ((unixMillis & 0xFFFF_FFFF_FFFFL) << 16) | 0x7000L | (random.nextLong() & 0x0FFFL);
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
