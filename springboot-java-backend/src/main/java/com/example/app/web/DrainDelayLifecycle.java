package com.example.app.web;

import com.example.app.config.AppProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Waits after readiness drops but before the connector stops accepting.
 *
 * <p>Boot already reports {@code REFUSING_TRAFFIC} when the context starts closing, and it already
 * lets in-flight requests finish. What it cannot know is how long the load balancer in front takes
 * to notice: without this pause the service stops accepting while traffic is still being routed to
 * it, and callers see connection failures during an orderly deployment.
 *
 * <p>A higher phase stops earlier, so this sits one above the web server's own shutdown, which runs
 * at {@code DEFAULT_PHASE - 1024}. That value is spelled out rather than read from
 * {@code WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE}, which Boot 4 has deprecated for
 * removal; {@link #stopsBeforeTheWebServer()} keeps the relationship checked.
 */
@Component
public class DrainDelayLifecycle implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(DrainDelayLifecycle.class);

    private final Duration drainDelay;
    private final Duration shutdownGrace;
    private volatile boolean running;

    public DrainDelayLifecycle(AppProperties properties) {
        this.drainDelay = properties.drainDelaySeconds();
        this.shutdownGrace = properties.shutdownGraceSeconds();
    }

    /** One phase above the web server's graceful shutdown, so this runs first. */
    static final int PHASE = DEFAULT_PHASE - 1023;

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        LOG.atInfo().setMessage("app.stopping")
                .addKeyValue("graceSeconds", shutdownGrace.toSeconds())
                .addKeyValue("drainSeconds", drainDelay.toSeconds())
                .log();
        sleepQuietly(drainDelay);
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static void sleepQuietly(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
