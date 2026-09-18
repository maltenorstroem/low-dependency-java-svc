package com.example.app;

import com.example.app.config.Config;
import com.example.app.config.ConfigException;
import com.example.app.observability.Log;

/** Process entry point: load config, start, and stop cleanly on SIGTERM/SIGINT. */
public final class Main {

    private static final Log LOG = Log.get(Main.class);

    private Main() {}

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler(
                (thread, error) -> LOG.error("thread.uncaught_exception", error, "thread", thread.getName()));

        Config config;
        try {
            config = Config.fromEnvironment(System.getenv());
        } catch (ConfigException e) {
            // Fail fast with a clear message; exit code 2 = configuration error.
            LOG.error("config.invalid", null, "reason", e.getMessage());
            System.exit(2);
            return;
        }

        Log.setThreshold(config.logLevel());
        Application.applyHttpServerLimits(config);

        Application app = new Application(config);
        try {
            app.start();
        } catch (Exception e) {
            LOG.error("app.start_failed", e);
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "shutdown"));
    }
}
