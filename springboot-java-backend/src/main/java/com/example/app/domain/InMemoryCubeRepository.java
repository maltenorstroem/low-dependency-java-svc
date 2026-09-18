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
 * Lock-free, bounded in-memory store, identical in shape to {@link InMemoryTaskRepository}: updates
 * are optimistic compare-and-set loops, so there are no locks to deadlock on and no lost updates.
 */
public final class InMemoryCubeRepository implements CubeRepository {

    private final ConcurrentSkipListMap<UUID, Cube> cubes = new ConcurrentSkipListMap<>(UuidV7.ORDER);
    private final AtomicInteger size = new AtomicInteger();
    private final int capacity;

    public InMemoryCubeRepository(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public Optional<Cube> find(UUID id) {
        return Optional.ofNullable(cubes.get(id));
    }

    @Override
    public void insert(Cube cube) {
        if (size.incrementAndGet() > capacity) {
            size.decrementAndGet();
            throw new CapacityExceededException("Cube store is full");
        }
        if (cubes.putIfAbsent(cube.id(), cube) != null) {
            size.decrementAndGet();
            throw new IllegalStateException("Duplicate id " + cube.id());
        }
    }

    @Override
    public Cube update(UUID id, LongPredicate versionCondition, UnaryOperator<Cube> change) {
        while (true) {
            Cube current = current(id, versionCondition);
            Cube next = change.apply(current);
            if (!next.id().equals(id) || next.version() != current.version() + 1) {
                throw new IllegalStateException("Change must keep the id and increment the version");
            }
            if (cubes.replace(id, current, next)) {
                return next;
            }
            // Lost a race: re-read and re-check the precondition.
        }
    }

    @Override
    public void delete(UUID id, LongPredicate versionCondition) {
        while (true) {
            Cube current = current(id, versionCondition);
            if (cubes.remove(id, current)) {
                size.decrementAndGet();
                return;
            }
        }
    }

    @Override
    public Page<Cube> list(Optional<UUID> after, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        NavigableMap<UUID, Cube> view = after.isPresent() ? cubes.tailMap(after.get(), false) : cubes;
        List<Cube> items = new ArrayList<>(Math.min(limit, 128));
        Iterator<Cube> it = view.values().iterator();
        while (it.hasNext() && items.size() < limit) {
            items.add(it.next());
        }
        Optional<UUID> next = it.hasNext() && !items.isEmpty()
                ? Optional.of(items.get(items.size() - 1).id())
                : Optional.empty();
        return new Page<>(items, next);
    }

    private Cube current(UUID id, LongPredicate versionCondition) {
        Cube current = cubes.get(id);
        if (current == null) {
            throw new NotFoundException("Cube not found");
        }
        if (!versionCondition.test(current.version())) {
            throw new VersionMismatchException("Cube was modified concurrently");
        }
        return current;
    }
}
