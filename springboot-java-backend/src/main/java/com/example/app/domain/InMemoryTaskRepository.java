package com.example.app.domain;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import java.util.function.UnaryOperator;

/**
 * Lock-free, bounded in-memory store. Updates are optimistic compare-and-set loops, so there are no
 * locks to deadlock on and no lost updates. Swap for a JDBC implementation ({@code java.sql} is
 * part of the JDK; only the driver is an external artifact) without touching anything else.
 */
public final class InMemoryTaskRepository implements TaskRepository {

    private final ConcurrentSkipListMap<UUID, Task> tasks = new ConcurrentSkipListMap<>(UuidV7.ORDER);
    private final AtomicInteger size = new AtomicInteger();
    private final int capacity;

    public InMemoryTaskRepository(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public Optional<Task> find(UUID id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public void insert(Task task) {
        if (size.incrementAndGet() > capacity) {
            size.decrementAndGet();
            throw new CapacityExceededException("Task store is full");
        }
        if (tasks.putIfAbsent(task.id(), task) != null) {
            size.decrementAndGet();
            throw new IllegalStateException("Duplicate id " + task.id());
        }
    }

    @Override
    public Task update(UUID id, LongPredicate versionCondition, UnaryOperator<Task> change) {
        while (true) {
            Task current = current(id, versionCondition);
            Task next = change.apply(current);
            if (!next.id().equals(id) || next.version() != current.version() + 1) {
                throw new IllegalStateException("Change must keep the id and increment the version");
            }
            if (tasks.replace(id, current, next)) {
                return next;
            }
            // Lost a race: re-read and re-check the precondition.
        }
    }

    @Override
    public void delete(UUID id, LongPredicate versionCondition) {
        while (true) {
            Task current = current(id, versionCondition);
            if (tasks.remove(id, current)) {
                size.decrementAndGet();
                return;
            }
        }
    }

    @Override
    public Page<Task> list(Optional<UUID> after, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        NavigableMap<UUID, Task> view = after.isPresent() ? tasks.tailMap(after.get(), false) : tasks;
        List<Task> items = new ArrayList<>(Math.min(limit, 128));
        Iterator<Task> it = view.values().iterator();
        while (it.hasNext() && items.size() < limit) {
            items.add(it.next());
        }
        Optional<UUID> next = it.hasNext() && !items.isEmpty()
                ? Optional.of(items.get(items.size() - 1).id())
                : Optional.empty();
        return new Page<>(items, next);
    }

    private Task current(UUID id, LongPredicate versionCondition) {
        Task current = tasks.get(id);
        if (current == null) {
            throw new NotFoundException("Task not found");
        }
        if (!versionCondition.test(current.version())) {
            throw new VersionMismatchException("Task was modified concurrently");
        }
        return current;
    }
}
