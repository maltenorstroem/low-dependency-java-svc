package com.example.app.api;

import com.example.app.config.AppProperties;
import com.example.app.config.RandomStreamProperties;
import com.example.app.web.ApiException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Produces the random-string streams, and owns the two bounds a stream needs that a request does
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
public class RandomStringStream implements SmartLifecycle {

    static final String EVENT = "random-string";

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * The web server's graceful-shutdown phase. Stopping alongside it, rather than before, keeps
     * streams running through the drain delay while the load balancer still routes here; the
     * server's own stop is asynchronous, so it is already waiting when these streams complete.
     */
    static final int PHASE = DEFAULT_PHASE - 1024;

    private final int stringLength;
    private final Duration maxDuration;
    private final Semaphore permits;
    private volatile Sinks.Empty<Void> shutdown = Sinks.empty();
    private volatile boolean running;

    public RandomStringStream(AppProperties properties, MeterRegistry registry) {
        RandomStreamProperties settings = properties.randomStream();
        this.stringLength = settings.stringLength();
        this.maxDuration = settings.maxDurationSeconds();
        int max = settings.maxConcurrentStreams();
        this.permits = new Semaphore(max);
        Gauge.builder("random_strings_streams_active", permits, p -> max - p.availablePermits())
                .description("Random-string streams currently open")
                .register(registry);
    }

    /**
     * A stream emitting one string every {@code interval}, ending after {@code count} events if
     * given, after the configured maximum duration, or at shutdown — whichever comes first.
     */
    public Flux<ServerSentEvent<String>> open(Duration interval, OptionalLong count) {
        if (!permits.tryAcquire()) {
            ApiException refusal = new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Too many open streams, retry later");
            refusal.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
            throw refusal;
        }
        Flux<ServerSentEvent<String>> events = Flux.interval(interval)
                .map(sequence -> ServerSentEvent.builder(next())
                        .id(Long.toString(sequence))
                        .event(EVENT)
                        .build());
        if (count.isPresent()) {
            events = events.take(count.getAsLong());
        }
        return events.take(maxDuration)
                .takeUntilOther(shutdown.asMono())
                .doFinally(signal -> permits.release());
    }

    private String next() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder value = new StringBuilder(stringLength);
        for (int i = 0; i < stringLength; i++) {
            value.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return value.toString();
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
