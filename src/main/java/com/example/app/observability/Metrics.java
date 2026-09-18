package com.example.app.observability;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Minimal, lock-free metrics in the Prometheus text exposition format (the de-facto standard,
 * also ingested by OpenTelemetry collectors). Label values are always route templates, never raw
 * paths, so cardinality is bounded by the number of routes.
 */
public final class Metrics {

    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private static final double[] BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};

    private record RequestKey(String method, String route, int status) {}

    private record RouteKey(String method, String route) {}

    private static final class Histogram {
        final LongAdder[] counts = new LongAdder[BUCKETS.length + 1]; // last slot = above all buckets
        final DoubleAdder sum = new DoubleAdder();

        Histogram() {
            for (int i = 0; i < counts.length; i++) {
                counts[i] = new LongAdder();
            }
        }

        void observe(double seconds) {
            int i = 0;
            while (i < BUCKETS.length && seconds > BUCKETS[i]) {
                i++;
            }
            counts[i].increment();
            sum.add(seconds);
        }
    }

    private final Map<String, LongAdder> authDecisions = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> jwksFetches = new ConcurrentHashMap<>();
    private final AtomicInteger jwksKeys = new AtomicInteger();
    private final Map<RequestKey, LongAdder> requests = new ConcurrentHashMap<>();
    private final Map<RouteKey, Histogram> durations = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final LongAdder rejected = new LongAdder();
    private final long startEpochSeconds;

    public Metrics(Clock clock) {
        this.startEpochSeconds = clock.instant().getEpochSecond();
    }

    public void requestStarted() {
        inFlight.incrementAndGet();
    }

    public void requestFinished(String method, String route, int status, long durationNanos) {
        inFlight.decrementAndGet();
        requests.computeIfAbsent(new RequestKey(method, route, status), k -> new LongAdder()).increment();
        durations.computeIfAbsent(new RouteKey(method, route), k -> new Histogram())
                .observe(durationNanos / 1_000_000_000.0);
    }

    public void requestRejected() {
        rejected.increment();
    }

    /**
     * @param outcome one of allowed, missing_token, invalid_token, insufficient_scope
     *
     * <p>Deliberately not labelled by subject, client, issuer or key id: those are either
     * unbounded or attacker-controlled, and this class exists to keep cardinality bounded.
     */
    public void authDecision(String outcome) {
        authDecisions.computeIfAbsent(outcome, k -> new LongAdder()).increment();
    }

    /** @param outcome success or failure */
    public void jwksFetch(String outcome) {
        jwksFetches.computeIfAbsent(outcome, k -> new LongAdder()).increment();
    }

    public void jwksKeysHeld(int count) {
        jwksKeys.set(count);
    }

    public byte[] render() {
        StringBuilder out = new StringBuilder(4096);

        header(out, "http_server_requests_total", "counter", "Total HTTP requests handled.");
        requests.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString()))
                .forEach(e -> {
                    RequestKey k = e.getKey();
                    out.append("http_server_requests_total{method=\"").append(escape(k.method()))
                            .append("\",route=\"").append(escape(k.route()))
                            .append("\",status=\"").append(k.status())
                            .append("\"} ").append(e.getValue().sum()).append('\n');
                });

        header(out, "http_server_request_duration_seconds", "histogram", "HTTP request latency.");
        durations.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString()))
                .forEach(e -> {
                    String labels = "method=\"" + escape(e.getKey().method())
                            + "\",route=\"" + escape(e.getKey().route()) + "\"";
                    Histogram h = e.getValue();
                    long cumulative = 0;
                    for (int i = 0; i < BUCKETS.length; i++) {
                        cumulative += h.counts[i].sum();
                        out.append("http_server_request_duration_seconds_bucket{").append(labels)
                                .append(",le=\"").append(BUCKETS[i]).append("\"} ").append(cumulative).append('\n');
                    }
                    cumulative += h.counts[BUCKETS.length].sum();
                    out.append("http_server_request_duration_seconds_bucket{").append(labels)
                            .append(",le=\"+Inf\"} ").append(cumulative).append('\n');
                    out.append("http_server_request_duration_seconds_sum{").append(labels).append("} ")
                            .append(h.sum.sum()).append('\n');
                    out.append("http_server_request_duration_seconds_count{").append(labels).append("} ")
                            .append(cumulative).append('\n');
                });

        header(out, "http_server_requests_in_flight", "gauge", "Requests currently being handled.");
        out.append("http_server_requests_in_flight ").append(inFlight.get()).append('\n');

        header(out, "http_server_requests_rejected_total", "counter", "Requests shed because of overload.");
        out.append("http_server_requests_rejected_total ").append(rejected.sum()).append('\n');

        if (!authDecisions.isEmpty()) {
            header(out, "http_server_auth_decisions_total", "counter", "Authorization decisions by outcome.");
            counters(out, "http_server_auth_decisions_total", "outcome", authDecisions);
        }
        if (!jwksFetches.isEmpty()) {
            header(out, "auth_jwks_fetches_total", "counter", "Fetches of the identity provider's key set.");
            counters(out, "auth_jwks_fetches_total", "outcome", jwksFetches);
            header(out, "auth_jwks_keys", "gauge", "Verification keys currently cached.");
            out.append("auth_jwks_keys ").append(jwksKeys.get()).append('\n');
        }

        Runtime rt = Runtime.getRuntime();
        header(out, "jvm_memory_heap_used_bytes", "gauge", "Used heap memory.");
        out.append("jvm_memory_heap_used_bytes ").append(rt.totalMemory() - rt.freeMemory()).append('\n');
        header(out, "jvm_memory_heap_max_bytes", "gauge", "Maximum heap memory.");
        out.append("jvm_memory_heap_max_bytes ").append(rt.maxMemory()).append('\n');
        header(out, "process_start_time_seconds", "gauge", "Start time of the process since unix epoch.");
        out.append("process_start_time_seconds ").append(startEpochSeconds).append('\n');

        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void counters(StringBuilder out, String name, String label, Map<String, LongAdder> values) {
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.append(name).append('{').append(label).append("=\"")
                        .append(escape(e.getKey())).append("\"} ").append(e.getValue().sum()).append('\n'));
    }

    private static void header(StringBuilder out, String name, String type, String help) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
