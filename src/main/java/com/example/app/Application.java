package com.example.app;

import com.example.app.api.CubeApi;
import com.example.app.api.TaskApi;
import com.example.app.config.Config;
import com.example.app.domain.Cube;
import com.example.app.domain.CubeService;
import com.example.app.domain.IdempotencyStore;
import com.example.app.domain.InMemoryCubeRepository;
import com.example.app.domain.InMemoryTaskRepository;
import com.example.app.domain.Task;
import com.example.app.domain.TaskService;
import com.example.app.domain.UuidV7;
import com.example.app.http.Dispatcher;
import com.example.app.http.Response;
import com.example.app.http.Router;
import com.example.app.observability.Log;
import com.example.app.observability.Metrics;
import com.example.app.http.Access;
import com.example.app.security.Authenticator;
import com.example.app.security.JwksClient;
import com.example.app.security.JwtVerifier;
import com.example.app.security.ScopeNames;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Composition root. All wiring is explicit plain Java: no reflection, no classpath scanning, no
 * annotations, nothing that can silently change behaviour when a library is upgraded.
 */
public final class Application {

    private static final Log LOG = Log.get(Application.class);

    private final Config config;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private HttpServer server;
    private ExecutorService executor;
    private JwksClient jwks;

    public Application(Config config) {
        this.config = config;
    }

    /**
     * Hardens the JDK HTTP server. These documented {@code jdk.httpserver} properties are read once
     * when the server class initialises, so this must run before the first server is created.
     * Values given explicitly with {@code -D} on the command line win.
     */
    static void applyHttpServerLimits(Config config) {
        String timeout = Long.toString(config.requestTimeout().toSeconds());
        setIfAbsent("sun.net.httpserver.maxReqTime", timeout); // max time to read a request
        setIfAbsent("sun.net.httpserver.maxRspTime", timeout); // max time to write a response
        setIfAbsent("sun.net.httpserver.idleInterval", "30");   // idle keep-alive connections
        setIfAbsent("sun.net.httpserver.maxReqHeaders", "100"); // header count limit
        // Bounds the Authorization header (and every other) before it reaches the application.
        setIfAbsent("sun.net.httpserver.maxReqHeaderSize", "16384");
        setIfAbsent("jdk.httpserver.maxConnections", Integer.toString(config.maxConnections()));
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    public synchronized void start() throws IOException {
        Clock clock = Clock.systemUTC();
        Metrics metrics = new Metrics(clock);
        var ids = UuidV7.generator(clock);
        var tasks = new TaskService(
                new InMemoryTaskRepository(config.maxTasks()),
                new IdempotencyStore<Task>(clock, config.idempotencyTtl(), config.maxIdempotencyKeys()),
                clock,
                ids);
        var cubes = new CubeService(
                new InMemoryCubeRepository(config.maxCubes()),
                new IdempotencyStore<Cube>(clock, config.idempotencyTtl(), config.maxIdempotencyKeys()),
                clock,
                ids);

        byte[] openApi = loadResource("/openapi.yaml");
        Authenticator authenticator = authenticator(metrics, clock);
        ScopeNames scopes = new ScopeNames(config.auth().scopePrefix());

        Router router = new Router();
        new TaskApi(tasks, scopes).register(router);
        new CubeApi(cubes, scopes).register(router);
        // Public by design. The probes must answer before anything else works, metrics are scraped
        // by an agent that holds no token, and requiring a token to read the contract that
        // explains how to get a token would be circular. Restrict /metrics at the ingress.
        router.get("/health/live", Access.PUBLIC, request -> Response.json(200, Map.of("status", "UP")));
        router.get("/health/ready", Access.PUBLIC, request -> ready.get()
                ? Response.json(200, Map.of("status", "UP"))
                : Response.json(503, Map.of("status", "DOWN")));
        router.get("/metrics", Access.PUBLIC,
                request -> Response.bytes(200, Metrics.CONTENT_TYPE, metrics.render()));
        router.get("/openapi.yaml", Access.PUBLIC,
                request -> Response.bytes(200, "application/yaml", openApi));

        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("http-", 0).factory());
        server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), config.backlog());
        server.createContext("/", new Dispatcher(router, metrics, authenticator, config.maxBodyBytes(),
                config.maxConcurrentRequests()));
        server.setExecutor(executor);
        server.start();
        ready.set(true);

        if (jwks != null) {
            // Best effort: the first request should not pay for the first key fetch, but the
            // identity provider being slow to answer must not hold up readiness either.
            Thread.ofVirtual().name("jwks-warmup").start(jwks::warmUp);
        }

        LOG.info("app.started",
                "host", config.host(),
                "port", port(),
                "java", Runtime.version().toString(),
                "cpus", Runtime.getRuntime().availableProcessors(),
                "maxHeapBytes", Runtime.getRuntime().maxMemory());
    }

    /**
     * Builds the authenticator, or the one that lets everything through. The disabled case is
     * logged at WARN on every boot so an unsecured deployment is never quiet about it.
     */
    private Authenticator authenticator(Metrics metrics, Clock clock) {
        if (!config.auth().enabled()) {
            LOG.warn("auth.disabled");
            return Authenticator.disabled();
        }
        var auth = config.auth();
        jwks = new JwksClient(auth.jwksUrl(), auth.jwksTtl(), auth.jwksMinRefresh(), auth.jwksTimeout(),
                auth.jwksMaxStale(), auth.jwksMaxBytes(), clock, metrics);
        JwtVerifier verifier = new JwtVerifier(jwks, auth.issuer(), auth.audience(), auth.clockLeeway(),
                auth.maxTokenBytes(), clock);
        LOG.info("auth.enabled",
                "issuer", auth.issuer(),
                "audience", auth.audience(),
                "jwksUrl", auth.jwksUrl().toString(),
                "scopePrefix", auth.scopePrefix());
        return Authenticator.of(verifier, auth.realm(), auth.maxTokenBytes(), metrics);
    }

    /** The actually bound port (useful when configured with port 0). */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * Graceful shutdown: report not-ready so load balancers stop routing, give them time to notice,
     * stop accepting connections, let in-flight requests finish, then stop the executor.
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true) || server == null) {
            return;
        }
        LOG.info("app.stopping", "graceSeconds", config.shutdownGrace().toSeconds());
        ready.set(false);
        sleepQuietly(config.drainDelay().toMillis());
        server.stop((int) Math.max(1, config.shutdownGrace().toSeconds()));
        executor.shutdown();
        try {
            if (!executor.awaitTermination(config.shutdownGrace().toSeconds(), TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (jwks != null) {
            jwks.close();
        }
        LOG.info("app.stopped");
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] loadResource(String name) {
        try (InputStream in = Application.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
