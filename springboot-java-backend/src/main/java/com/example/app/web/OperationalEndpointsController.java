package com.example.app.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

/**
 * The operational surface at the paths the contract publishes.
 *
 * <p>Actuator serves the same information under {@code /actuator}, and those paths are exposed too.
 * These exist because the deployment manifest, the ingress rule that restricts {@code /metrics} and
 * the smoke script are all written against them, and because keeping them identical is what lets
 * one manifest and one script drive either implementation.
 *
 * <p>The probes read {@link ApplicationAvailability} directly rather than forwarding to actuator, so
 * the bodies stay exactly {@code {"status":"UP"}}. Readiness is what graceful shutdown moves first,
 * which is what gives the load balancer time to stop routing here.
 *
 * <p>All four are public by design: probes must answer before anything else works, metrics are
 * scraped by an agent that holds no token, and requiring a token to read the contract that explains
 * how to get a token would be circular. Restrict {@code /metrics} at the ingress.
 */
@RestController
public class OperationalEndpointsController {

    private static final Map<String, String> UP = Map.of("status", "UP");
    private static final Map<String, String> DOWN = Map.of("status", "DOWN");

    private final ApplicationAvailability availability;
    private final byte[] openApi;

    public OperationalEndpointsController(ApplicationAvailability availability) throws IOException {
        this.availability = availability;
        // Read once at startup: a contract that cannot be loaded should be noticed then, not on the
        // first request for it.
        try (InputStream in = new ClassPathResource("openapi.yaml").getInputStream()) {
            this.openApi = in.readAllBytes();
        }
    }

    @GetMapping(path = "/health/live", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, String>> live() {
        return availability.getLivenessState() == LivenessState.CORRECT
                ? ResponseEntity.ok(UP)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(DOWN);
    }

    @GetMapping(path = "/health/ready", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, String>> ready() {
        return availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC
                ? ResponseEntity.ok(UP)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(DOWN);
    }

    /** Prometheus scrapes this; actuator renders it. */
    @GetMapping("/metrics")
    ModelAndView metrics() {
        return new ModelAndView("forward:/actuator/prometheus");
    }

    @GetMapping(path = "/openapi.yaml", produces = "application/yaml")
    ResponseEntity<byte[]> openApi() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/yaml")).body(openApi);
    }
}
