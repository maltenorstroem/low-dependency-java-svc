package com.example.app.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable aggregate. {@code version} starts at 1 and increases by one on every change. */
public record Task(UUID id, String title, boolean completed, long version, Instant createdAt, Instant updatedAt) {

    public Task {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }

    public static Task create(UUID id, TaskInput input, Instant now) {
        return new Task(id, input.title(), input.completed(), 1, now, now);
    }

    public Task apply(TaskInput input, Instant now) {
        Instant updated = now.isBefore(createdAt) ? createdAt : now; // never go back in time
        return new Task(id, input.title(), input.completed(), Math.addExact(version, 1), createdAt, updated);
    }
}
