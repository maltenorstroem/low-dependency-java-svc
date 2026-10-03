package com.example.app.api;

import com.example.app.config.AppProperties;
import com.example.app.config.NauticalFlagsProperties;
import com.example.app.web.AllowedQueryParams;
import com.example.app.web.ApiException;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * HTTP adapter for the nautical-flag stream: the caller's text as server-sent events, one signal
 * flag per character, at an interval the caller chooses within configured bounds. Returning a
 * {@link Flux} is enough for Spring MVC to hold the response open and write each element as it
 * arrives. Scopes are enforced by the security filter chain, not here.
 */
@RestController
public class NauticalFlagController {

    static final String PATH = "/v1/nautical-flags";

    private final NauticalFlagStream stream;
    private final NauticalFlags flags;
    private final NauticalFlagsProperties settings;

    public NauticalFlagController(NauticalFlagStream stream, NauticalFlags flags, AppProperties properties) {
        this.stream = stream;
        this.flags = flags;
        this.settings = properties.nauticalFlags();
    }

    @GetMapping(path = PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @AllowedQueryParams({"text", "intervalMs"})
    Flux<ServerSentEvent<NauticalFlag>> stream(
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String intervalMs) {

        List<NauticalFlag> translated = parseText(text);
        Duration interval = Duration.ofMillis(parseInterval(intervalMs));
        return stream.open(interval, translated);
    }

    /**
     * Optional to the framework so that a missing text is the same 400 from here as any other bad
     * one, rather than a missing-parameter error worded by the framework.
     */
    private List<NauticalFlag> parseText(String raw) {
        if (raw != null && !raw.isEmpty() && raw.length() <= settings.maxTextLength()) {
            try {
                return flags.translate(raw);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "text must be 1 to " + settings.maxTextLength()
                + " characters from A-Z, 0-9 and space");
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
}
