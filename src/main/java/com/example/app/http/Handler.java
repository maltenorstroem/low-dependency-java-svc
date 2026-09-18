package com.example.app.http;

/** Handles one matched request. Throwing is fine: the dispatcher maps every exception to a problem response. */
@FunctionalInterface
public interface Handler {
    Response handle(Request request) throws Exception;
}
