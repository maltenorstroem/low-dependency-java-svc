package com.example.app.config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Derives {@code app.auth.enabled} from {@code APP_AUTH_ISSUER} before anything reads it.
 *
 * <p>This runs as an {@link EnvironmentPostProcessor} rather than as a default on
 * {@link AuthProperties} because the choice between the secured and the open security filter chain
 * is made with {@code @ConditionalOnProperty}, and conditions are evaluated long before
 * configuration properties are bound. Deriving it here means there is exactly one rule, applied
 * once, that both the condition and the bound record see.
 *
 * <p>It also translates {@code APP_LOG_LEVEL} onto {@code logging.level.root}. The zero-dependency
 * service takes {@code java.lang.System.Logger.Level} names, which spell warnings {@code WARNING};
 * Logback spells it {@code WARN}. Accepting both keeps one environment working for either service.
 */
public class AuthEnabledEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String SOURCE_NAME = "derived-app-properties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> derived = new HashMap<>();

        if (environment.getProperty("app.auth.enabled") == null) {
            String issuer = environment.getProperty("app.auth.issuer", "");
            derived.put("app.auth.enabled", Boolean.toString(!issuer.isBlank()));
        }

        String level = environment.getProperty("app.log-level");
        if (level != null && !level.isBlank()) {
            derived.put("logging.level.root", normalizeLevel(level));
        }

        if (!derived.isEmpty()) {
            // Added first so it loses to anything set explicitly on the command line.
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, derived));
        }
    }

    private static String normalizeLevel(String level) {
        String upper = level.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "WARNING" -> "WARN";
            case "ALL" -> "TRACE";
            default -> upper;
        };
    }

    @Override
    public int getOrder() {
        // After the environment's own property sources are in place, so the issuer is visible.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
