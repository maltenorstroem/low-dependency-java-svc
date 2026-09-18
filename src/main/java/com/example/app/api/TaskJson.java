package com.example.app.api;

import com.example.app.domain.Task;
import com.example.app.domain.TaskInput;
import com.example.app.domain.ValidationException;
import com.example.app.domain.ValidationException.Violation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit mapping between JSON and the domain. No reflection, no annotations, no surprises. */
final class TaskJson {

    private static final Set<String> WRITABLE = Set.of("title", "completed");
    /** Accepted and ignored, so clients can PUT back what they got from GET. */
    private static final Set<String> READ_ONLY = Set.of("id", "version", "createdAt", "updatedAt");
    private static final int MAX_REPORTED_VIOLATIONS = 20;

    private TaskJson() {}

    static Map<String, Object> toJson(Task task) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", task.id());
        json.put("title", task.title());
        json.put("completed", task.completed());
        json.put("version", task.version());
        json.put("createdAt", task.createdAt());
        json.put("updatedAt", task.updatedAt());
        return json;
    }

    /** Checks shape and types (unknown fields are errors), then lets the domain check the rules. */
    static TaskInput toInput(Object body) {
        if (!(body instanceof Map<?, ?> map)) {
            throw new ValidationException(List.of(new Violation("", "must be a JSON object")));
        }
        List<Violation> violations = new ArrayList<>();
        for (Object key : map.keySet()) {
            String name = (String) key;
            if (!WRITABLE.contains(name) && !READ_ONLY.contains(name)) {
                add(violations, new Violation(pointer(name), "is not a known field"));
            }
        }
        Object title = map.get("title");
        if (title != null && !(title instanceof String)) {
            add(violations, new Violation("/title", "must be a string"));
        }
        Object completed = map.get("completed");
        if (completed != null && !(completed instanceof Boolean)) {
            add(violations, new Violation("/completed", "must be a boolean"));
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return new TaskInput((String) title, Boolean.TRUE.equals(completed));
    }

    private static void add(List<Violation> violations, Violation violation) {
        if (violations.size() < MAX_REPORTED_VIOLATIONS) {
            violations.add(violation);
        }
    }

    /** JSON Pointer (RFC 6901) for a top-level member; long names are truncated. */
    private static String pointer(String name) {
        String shortened = name.length() > 64 ? name.substring(0, 64) : name;
        return "/" + shortened.replace("~", "~0").replace("/", "~1");
    }
}
