package com.example.app.domain;

import java.util.Optional;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.function.UnaryOperator;

/**
 * Persistence port. Implementations must make {@link #update} and {@link #delete} atomic
 * compare-and-set operations on {@code version}.
 *
 * <p>{@code versionCondition} is tested against the current version (from {@code If-Match}).
 */
public interface TaskRepository {

    Optional<Task> find(UUID id);

    /** @throws CapacityExceededException if the store is full */
    void insert(Task task);

    /** @throws NotFoundException, VersionMismatchException */
    Task update(UUID id, LongPredicate versionCondition, UnaryOperator<Task> change);

    /** @throws NotFoundException, VersionMismatchException */
    void delete(UUID id, LongPredicate versionCondition);

    /** Tasks in id order (UUIDv7, i.e. creation order), strictly after {@code after} if present. */
    Page<Task> list(Optional<UUID> after, int limit);
}
