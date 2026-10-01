package com.example.app.api;

import com.example.app.config.AppProperties;
import com.example.app.config.RandomStreamProperties;
import com.example.app.web.AllowedQueryParams;
import com.example.app.web.ApiException;
import java.time.Duration;
import java.util.OptionalLong;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * HTTP adapter for the random-string stream: server-sent events, one random string per event, at
 * an interval the caller chooses within configured bounds. Returning a {@link Flux} is enough for
 * Spring MVC to hold the response open and write each element as it arrives. Scopes are enforced
 * by the security filter chain, not here.
 */
@RestController
public class RandomStringController {

    static final String PATH = "/v1/random-strings";
    static final long MAX_COUNT = 100_000;

    private final RandomStringStream stream;
    private final RandomStreamProperties settings;

    public RandomStringController(RandomStringStream stream, AppProperties properties) {
        this.stream = stream;
        this.settings = properties.randomStream();
    }

    @GetMapping(path = PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @AllowedQueryParams({"intervalMs", "count"})
    Flux<ServerSentEvent<String>> stream(
            @RequestParam(required = false) String intervalMs,
            @RequestParam(required = false) String count) {

        Duration interval = Duration.ofMillis(parseInterval(intervalMs));
        return stream.open(interval, parseCount(count));
    }

    /**
     * Taken as text so that a non-numeric value is one 400 from here rather than a type-mismatch
     * from the framework, keeping the message identical for every bad interval.
     */
    private long parseInterval(String raw) {
        if (raw == null) {
            return settings.defaultIntervalMillis();
        }
        try {
            long interval = Long.parseLong(raw);
            if (interval >= settings.minIntervalMillis() && interval <= settings.maxIntervalMillis()) {
                return interval;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "intervalMs must be an integer between "
                + settings.minIntervalMillis() + " and " + settings.maxIntervalMillis());
    }

    private static OptionalLong parseCount(String raw) {
        if (raw == null) {
            return OptionalLong.empty();
        }
        try {
            long count = Long.parseLong(raw);
            if (count >= 1 && count <= MAX_COUNT) {
                return OptionalLong.of(count);
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "count must be an integer between 1 and " + MAX_COUNT);
    }
}
