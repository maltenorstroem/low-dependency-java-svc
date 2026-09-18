/**
 * Reference backend. Depends on nothing but the JDK.
 *
 * <p>{@code jdk.httpserver} is a supported, exported JDK module (not an internal API) and has
 * been part of every JDK since Java 6.
 */
module com.example.app {
    requires jdk.httpserver;

    // Outbound HTTPS for fetching the identity provider's JWKS (public signing keys).
    requires java.net.http;

    // Routes all JDK platform logging (including the HTTP server's) into our JSON log stream.
    provides java.lang.System.LoggerFinder
            with com.example.app.observability.JsonLoggerFinder;
}
