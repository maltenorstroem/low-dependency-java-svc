package com.example.app.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import com.example.app.testing.HttpTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * With virtual threads, Tomcat's thread pool no longer bounds concurrency, so this semaphore is the
 * only backpressure the service has. One permit makes that observable.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.max-concurrent-requests=1")
class LoadSheddingTest extends HttpTestSupport {

    @Test
    void refusesBeyondTheConcurrencyLimitAndRecovers() throws Exception {
        int callers = 12;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger shed = new AtomicInteger();
        List<HttpResponse<String>> refusals = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                String title = "shed " + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    HttpResponse<String> response = send("POST", "/v1/tasks", "{\"title\":\"" + title + "\"}");
                    if (response.statusCode() == 503) {
                        shed.incrementAndGet();
                        synchronized (refusals) {
                            refusals.add(response);
                        }
                    } else {
                        accepted.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertEquals(callers, accepted.get() + shed.get());
        assertTrue(shed.get() > 0, "at least one caller met the limit");
        for (HttpResponse<String> refusal : refusals) {
            assertEquals("1", header(refusal, "Retry-After"));
            assertProblem(refusal, 503);
        }
        // The permit is released in a finally, so the service is usable immediately afterwards.
        assertEquals(201, send("POST", "/v1/tasks", "{\"title\":\"after\"}").statusCode());
    }
}
