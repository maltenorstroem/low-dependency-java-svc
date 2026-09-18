package com.example.app.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;

/**
 * Pins the one assumption {@link DrainDelayLifecycle} makes about Boot's internals. The constant it
 * compares against is deprecated for removal, so this test is what will notice when it goes or
 * changes value, rather than a deployment that stops accepting traffic too early.
 */
@SuppressWarnings("removal")
class DrainDelayLifecycleTest {

    @Test
    void stopsBeforeTheWebServer() {
        assertTrue(DrainDelayLifecycle.PHASE > WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE,
                "a higher phase stops earlier, so the drain delay must outrank the web server's shutdown");
    }
}
