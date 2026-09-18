package com.example.app.api;

import com.example.app.domain.Task;
import com.example.app.domain.TaskInput;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * The JSON representation of a task. The server-owned fields are declared but read-only, so a
 * client can PUT back exactly what a GET returned: they are accepted and ignored rather than
 * rejected as unknown, while unknown fields are still errors. They are boxed for the same reason
 * {@code completed} is: a read-only primitive would make an absent field a binding failure.
 */
public record TaskResource(

        @JsonProperty(access = JsonProperty.Access.READ_ONLY) UUID id,

        String title,

        Boolean completed,

        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Long version,

        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Instant createdAt,

        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Instant updatedAt) {

    static TaskResource of(Task task) {
        return new TaskResource(task.id(), task.title(), task.completed(), task.version(),
                task.createdAt(), task.updatedAt());
    }

    /**
     * The domain constructor owns the rules (NFC, length, control characters) and the pointers.
     * {@code completed} is boxed because the contract makes it optional with a default: as a
     * primitive, omitting it would be a binding error rather than a false.
     */
    TaskInput toInput() {
        return new TaskInput(title, Boolean.TRUE.equals(completed));
    }
}
