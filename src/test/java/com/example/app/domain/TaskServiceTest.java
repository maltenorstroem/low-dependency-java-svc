package com.example.app.domain;

import static com.example.app.testing.Assert.assertEquals;
import static com.example.app.testing.Assert.assertThrows;
import static com.example.app.testing.Assert.assertTrue;

import com.example.app.testing.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final TaskService service = newService(1_000);

    private TaskService newService(int capacity) {
        return new TaskService(
                new InMemoryTaskRepository(capacity),
                new IdempotencyStore<Task>(clock, Duration.ofHours(1), 100),
                clock,
                UuidV7.generator(Clock.systemUTC()));
    }

    @Test
    void normalizesAndValidatesInput() {
        assertEquals("Buy milk", new TaskInput("  Buy milk\t", false).title());
        assertEquals("\u00e9", new TaskInput("e\u0301", false).title()); // NFC
        assertEquals(200, new TaskInput("x".repeat(200), false).title().length());

        ValidationException e = assertThrows(ValidationException.class, () -> new TaskInput(null, false));
        assertEquals("/title", e.violations().get(0).pointer());
        assertThrows(ValidationException.class, () -> new TaskInput("   ", false));
        assertThrows(ValidationException.class, () -> new TaskInput("x".repeat(201), false));
        assertThrows(ValidationException.class, () -> new TaskInput("a\u0000b", false));
    }

    @Test
    void createReadReplaceDelete() {
        Task created = service.create(new TaskInput("one", false), Optional.empty());
        assertEquals(1L, created.version());
        assertEquals(created, service.get(created.id()));

        Task updated = service.replace(created.id(), new TaskInput("two", true), v -> v == 1);
        assertEquals(2L, updated.version());
        assertEquals("two", updated.title());
        assertEquals(created.createdAt(), updated.createdAt());

        assertThrows(VersionMismatchException.class,
                () -> service.replace(created.id(), new TaskInput("three", true), v -> v == 1));
        assertThrows(VersionMismatchException.class, () -> service.delete(created.id(), v -> v == 1));

        service.delete(created.id(), v -> v == 2);
        assertThrows(NotFoundException.class, () -> service.get(created.id()));
        assertThrows(NotFoundException.class, () -> service.delete(created.id(), v -> true));
    }

    @Test
    void concurrentUpdatesWithSameVersionHaveExactlyOneWinner() throws Exception {
        Task task = service.create(new TaskInput("race", false), Optional.empty());
        int writers = 32;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger losers = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String title = "writer " + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.replace(task.id(), new TaskInput(title, true), v -> v == 1);
                        winners.incrementAndGet();
                    } catch (VersionMismatchException e) {
                        losers.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertEquals(1, winners.get());
        assertEquals(writers - 1, losers.get());
        assertEquals(2L, service.get(task.id()).version());
    }

    @Test
    void enforcesCapacity() {
        TaskService small = newService(2);
        small.create(new TaskInput("a", false), Optional.empty());
        Task b = small.create(new TaskInput("b", false), Optional.empty());
        assertThrows(CapacityExceededException.class, () -> small.create(new TaskInput("c", false), Optional.empty()));
        small.delete(b.id(), v -> true);
        small.create(new TaskInput("c", false), Optional.empty());
    }

    @Test
    void paginatesInCreationOrderWithoutGapsOrDuplicates() throws Exception {
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            created.add(service.create(new TaskInput("t" + i, false), Optional.empty()).id());
            Thread.sleep(1); // distinct UUIDv7 timestamps => deterministic order
        }
        List<UUID> seen = new ArrayList<>();
        Optional<UUID> after = Optional.empty();
        int pages = 0;
        do {
            Page<Task> page = service.list(after, 10);
            page.items().forEach(t -> seen.add(t.id()));
            after = page.continueAfter();
            pages++;
        } while (after.isPresent());
        assertEquals(created, seen);
        assertEquals(3, pages);
        assertThrows(IllegalArgumentException.class, () -> service.list(Optional.empty(), 0));
        assertThrows(IllegalArgumentException.class, () -> service.list(Optional.empty(), TaskService.MAX_PAGE_SIZE + 1));
    }

    @Test
    void idempotencyKeyReplaysAndDetectsMisuse() {
        Task first = service.create(new TaskInput("pay", false), Optional.of("key-1"));
        Task again = service.create(new TaskInput("pay", false), Optional.of("key-1"));
        assertEquals(first, again);
        assertEquals(1, service.list(Optional.empty(), 100).items().size());
        assertThrows(IdempotencyKeyReusedException.class,
                () -> service.create(new TaskInput("different", false), Optional.of("key-1")));
    }

    @Test
    void failedIdempotentAttemptIsNotRemembered() {
        IdempotencyStore<Task> store = new IdempotencyStore<>(clock, Duration.ofHours(1), 10);
        assertThrows(IllegalStateException.class, () -> store.execute("k", "fp", () -> {
            throw new IllegalStateException("boom");
        }));
        Task task = Task.create(UUID.randomUUID(), new TaskInput("ok", false), clock.instant());
        assertEquals(task, store.execute("k", "fp", () -> task));
    }

    @Test
    void concurrentDuplicatesExecuteOnce() throws Exception {
        IdempotencyStore<Task> store = new IdempotencyStore<>(clock, Duration.ofHours(1), 10);
        AtomicInteger executions = new AtomicInteger();
        Set<Task> results = java.util.Collections.synchronizedSet(new HashSet<>());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                pool.submit(() -> results.add(store.execute("same", "fp", () -> {
                    executions.incrementAndGet();
                    sleep(50);
                    return Task.create(UUID.randomUUID(), new TaskInput("x", false), clock.instant());
                })));
            }
        }
        assertEquals(1, executions.get());
        assertEquals(1, results.size());
    }

    @Test
    void uuidV7HasCorrectLayoutAndOrder() {
        UUID early = UuidV7.create(1_000L, new java.util.Random(1));
        UUID late = UuidV7.create(2_000L, new java.util.Random(2));
        assertEquals(7, early.version());
        assertEquals(2, early.variant());
        assertTrue(UuidV7.ORDER.compare(early, late) < 0, "time ordering");
        assertEquals(1_000L, early.getMostSignificantBits() >>> 16);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
