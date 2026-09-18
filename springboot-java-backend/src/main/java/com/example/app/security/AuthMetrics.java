package com.example.app.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Counts authentication outcomes. Spring Security publishes events but no meters, so the labels the
 * dashboards and alerts use have to be registered here.
 *
 * <p>The label set is fixed and small on purpose: an outcome derived from a token would let a
 * caller create unbounded time series just by sending rubbish.
 */
@Component
public class AuthMetrics {

    public enum Outcome {
        ALLOWED("allowed"),
        MISSING_TOKEN("missing_token"),
        INVALID_TOKEN("invalid_token"),
        INSUFFICIENT_SCOPE("insufficient_scope"),
        KEYS_UNAVAILABLE("keys_unavailable");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }
    }

    private final Map<Outcome, Counter> counters = new HashMap<>();

    public AuthMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("http_server_auth_decisions_total")
                    .description("Authentication and authorization decisions")
                    .tag("outcome", outcome.label)
                    .register(registry));
        }
    }

    public void record(Outcome outcome) {
        counters.get(outcome).increment();
    }

    @EventListener
    void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        record(Outcome.ALLOWED);
    }
}
