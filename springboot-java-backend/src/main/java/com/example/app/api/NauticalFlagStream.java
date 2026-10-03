package com.example.app.api;

import com.example.app.config.AppProperties;
import com.example.app.config.NauticalFlagsProperties;
import com.example.app.web.ApiException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Produces the nautical-flag streams, and owns the two bounds a stream needs that a request does
 * not.
 *
 * <p>The number of open streams is capped here because {@code LoadSheddingFilter} cannot do it:
 * its permit is released as soon as the handler returns, which for a stream is the moment it
 * starts, not the moment it ends. And every stream is completed when the application stops, so
 * that graceful shutdown ends them cleanly rather than waiting out its grace period and cutting
 * them off.
 *
 * <p>Deliberately not in {@code domain}, which is byte-identical to the sibling service.
 */
@Component
public class NauticalFlagStream implements SmartLifecycle {

    static final String EVENT = "nautical-flag";

    /**
     * The web server's graceful-shutdown phase. Stopping alongside it, rather than before, keeps
     * streams running through the drain delay while the load balancer still routes here; the
     * server's own stop is asynchronous, so it is already waiting when these streams complete.
     */
    static final int PHASE = DEFAULT_PHASE - 1024;

    private final Duration maxDuration;
    private final Semaphore permits;
    private volatile Sinks.Empty<Void> shutdown = Sinks.empty();
    private volatile boolean running;

    public NauticalFlagStream(AppProperties properties, MeterRegistry registry) {
        NauticalFlagsProperties settings = properties.nauticalFlags();
        this.maxDuration = settings.maxDurationSeconds();
        int max = settings.maxConcurrentStreams();
        this.permits = new Semaphore(max);
        Gauge.builder("nautical_flags_streams_active", permits, p -> max - p.availablePermits())
                .description("Nautical-flag streams currently open")
                .register(registry);
    }

    /**
     * A stream emitting one flag every {@code interval}, ending after the last flag, after the
     * configured maximum duration, or at shutdown — whichever comes first.
     */
    public Flux<ServerSentEvent<NauticalFlag>> open(Duration interval, List<NauticalFlag> flags) {
        if (!permits.tryAcquire()) {
            ApiException refusal = new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Too many open streams, retry later");
            refusal.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
            throw refusal;
        }
        return Flux.interval(interval)
                .take(flags.size())
                .map(sequence -> ServerSentEvent.builder(flags.get(sequence.intValue()))
                        .id(Long.toString(sequence))
                        .event(EVENT)
                        .build())
                .take(maxDuration)
                .takeUntilOther(shutdown.asMono())
                .doFinally(signal -> permits.release());
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public void start() {
        shutdown = Sinks.empty();
        running = true;
    }

    /** Completes every open stream; each client sees a normal end of stream. */
    @Override
    public void stop() {
        shutdown.tryEmitEmpty();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
