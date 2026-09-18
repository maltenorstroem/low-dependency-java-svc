package com.example.app.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One page of results plus the id to continue after, if there are more. */
public record Page<T>(List<T> items, Optional<UUID> continueAfter) {

    public Page {
        items = List.copyOf(items);
    }
}
