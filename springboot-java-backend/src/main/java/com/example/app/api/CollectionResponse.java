package com.example.app.api;

import java.util.List;

/**
 * One page of a collection. {@code nextCursor} is always present, null on the last page, which is
 * why the mapper keeps null properties rather than omitting them.
 */
public record CollectionResponse<T>(List<T> items, String nextCursor) {
}
