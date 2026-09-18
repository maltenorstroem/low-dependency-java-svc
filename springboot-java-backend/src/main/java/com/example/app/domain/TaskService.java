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
public final class TaskService {

    public static final int MAX_PAGE_SIZE = 100;

    private final TaskRepository repository;
    private final IdempotencyStore<Task> idempotency;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public TaskService(
            TaskRepository repository, IdempotencyStore<Task> idempotency, Clock clock, Supplier<UUID> ids) {
        this.repository = Objects.requireNonNull(repository);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.clock = Objects.requireNonNull(clock);
        this.ids = Objects.requireNonNull(ids);
    }

    public Task create(TaskInput input, Optional<String> idempotencyKey) {
        Supplier<Task> action = () -> {
            Task task = Task.create(ids.get(), input, clock.instant());
            repository.insert(task);
            return task;
        };
        return idempotencyKey.isPresent()
                ? idempotency.execute(idempotencyKey.get(), fingerprint(input), action)
                : action.get();
    }

    public Task get(UUID id) {
        return repository.find(id).orElseThrow(() -> new NotFoundException("Task not found"));
    }

    public Page<Task> list(Optional<UUID> after, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit out of range");
        }
        return repository.list(after, limit);
    }

    public Task replace(UUID id, TaskInput input, LongPredicate versionCondition) {
        return repository.update(id, versionCondition, current -> current.apply(input, clock.instant()));
    }

    public void delete(UUID id, LongPredicate versionCondition) {
        repository.delete(id, versionCondition);
    }

    private static String fingerprint(TaskInput input) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(input.title().getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update((byte) (input.completed() ? 1 : 0));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every Java platform", e);
        }
    }
}
