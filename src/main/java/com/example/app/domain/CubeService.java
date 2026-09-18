package com.example.app.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/** Application service: the use cases. Knows nothing about HTTP or JSON. */
public final class CubeService {

    public static final int MAX_PAGE_SIZE = 100;

    private final CubeRepository repository;
    private final IdempotencyStore<Cube> idempotency;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public CubeService(
            CubeRepository repository, IdempotencyStore<Cube> idempotency, Clock clock, Supplier<UUID> ids) {
        this.repository = Objects.requireNonNull(repository);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    public Cube create(CubeInput input, Optional<String> idempotencyKey) {
        Supplier<Cube> action = () -> {
            Cube cube = Cube.create(ids.get(), input, clock.instant());
            repository.insert(cube);
            return cube;
        };
        return idempotencyKey.isPresent()
                ? idempotency.execute(idempotencyKey.get(), fingerprint(input), action)
                : action.get();
    }

    public Cube get(UUID id) {
        return repository.find(id).orElseThrow(() -> new NotFoundException("Cube not found"));
    }

    public Page<Cube> list(Optional<UUID> after, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit out of range");
        }
        return repository.list(after, limit);
    }

    public Cube replace(UUID id, CubeInput input, LongPredicate versionCondition) {
        return repository.update(id, versionCondition, current -> current.apply(input, clock.instant()));
    }

    public void delete(UUID id, LongPredicate versionCondition) {
        repository.delete(id, versionCondition);
    }

    /** Covers every field a client can send, so a replayed key with a changed body is detected. */
    private static String fingerprint(CubeInput input) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(input.description().getBytes(StandardCharsets.UTF_8));
            CubeDefinition cube = input.cube();
            for (double edge : new double[] {cube.length(), cube.breadth(), cube.height()}) {
                sha.update((byte) 0);
                sha.update(Double.toString(edge).getBytes(StandardCharsets.UTF_8));
            }
            sha.update((byte) 0);
            sha.update(cube.colour().toString().getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(cube.material().name().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every Java platform", e);
        }
    }
}
