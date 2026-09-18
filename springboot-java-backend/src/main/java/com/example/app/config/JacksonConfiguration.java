package com.example.app.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;

/**
 * Strict JSON, matching what the hand-written parser in the sibling service enforces.
 *
 * <p>Jackson's defaults are permissive where an API contract should not be: unknown fields are
 * ignored, duplicate keys silently take the last value, and a case-insensitive match will accept
 * {@code Title} for {@code title}. Each of those turns a client mistake into a response that looks
 * successful but is not what was asked for.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictJson() {
        return builder -> builder
                // A field the contract does not declare is a client error, not something to drop.
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                // {"a":1}{"b":2} is two documents; accepting the first silently is worse than a 400.
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                // RFC 8259 allows duplicate keys; RFC 7493 (I-JSON) does not, and neither do we.
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                // 1.5 is not an integer. Left on, Jackson truncates it to 1 and the response
                // looks successful while holding a value the client never sent.
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
        // Timestamps need no setting: Jackson 3 writes java.time values as RFC 3339 strings by
        // default, where Jackson 2 needed WRITE_DATES_AS_TIMESTAMPS turned off.
    }
}
