package com.example.app.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The complete set of query parameters a handler accepts. Anything else, or any parameter given
 * more than once, is a 400 — see {@link QueryParamInterceptor}.
 *
 * <p>Spring ignores unknown query parameters by default, which quietly turns a client's typo into
 * a silently different result: {@code ?limt=100} would return the default page size and look like
 * a server bug. Declaring the accepted set keeps that a client error.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AllowedQueryParams {
    String[] value();
}
