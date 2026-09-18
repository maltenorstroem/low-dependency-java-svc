package com.example.app.config;

import com.example.app.domain.Cube;
import com.example.app.domain.CubeRepository;
import com.example.app.domain.CubeService;
import com.example.app.domain.IdempotencyStore;
import com.example.app.domain.InMemoryCubeRepository;
import com.example.app.domain.InMemoryTaskRepository;
import com.example.app.domain.Task;
import com.example.app.domain.TaskRepository;
import com.example.app.domain.TaskService;
import com.example.app.domain.UuidV7;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the domain. The domain classes carry no annotations and know nothing about Spring, so this
 * is the one place that knows how they fit together — the same job the composition root does in
 * the sibling service, minus the HTTP plumbing.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties.class)
public class DomainConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Supplier<UUID> identifiers(Clock clock) {
        return UuidV7.generator(clock);
    }

    @Bean
    TaskRepository taskRepository(AppProperties properties) {
        return new InMemoryTaskRepository(properties.maxTasks());
    }

    @Bean
    CubeRepository cubeRepository(AppProperties properties) {
        return new InMemoryCubeRepository(properties.maxCubes());
    }

    @Bean
    IdempotencyStore<Task> taskIdempotencyStore(Clock clock, AppProperties properties) {
        return new IdempotencyStore<>(clock, properties.idempotencyTtlSeconds(),
                properties.maxIdempotencyKeys());
    }

    @Bean
    IdempotencyStore<Cube> cubeIdempotencyStore(Clock clock, AppProperties properties) {
        return new IdempotencyStore<>(clock, properties.idempotencyTtlSeconds(),
                properties.maxIdempotencyKeys());
    }

    @Bean
    TaskService taskService(TaskRepository repository, IdempotencyStore<Task> idempotency,
            Clock clock, Supplier<UUID> identifiers) {
        return new TaskService(repository, idempotency, clock, identifiers);
    }

    @Bean
    CubeService cubeService(CubeRepository repository, IdempotencyStore<Cube> idempotency,
            Clock clock, Supplier<UUID> identifiers) {
        return new CubeService(repository, idempotency, clock, identifiers);
    }
}
