# ADR-0001: No third-party dependencies

- Status: accepted

## Context

The service must keep running and building for years with minimal maintenance. In typical Java services most security advisories, upgrade work and "works on my machine" breakage comes from the dependency graph: web frameworks, JSON binders, logging backends, build plugins.

## Decision

Use only JDK modules: `java.base` and `jdk.httpserver` at runtime; `javac`, `jar`, `jlink` for the build; a minimal in-repo test runner for tests. Write the small pieces the JDK lacks (strict JSON, routing, metrics, JSON logging) ourselves, each in a single, heavily tested class.

## Consequences

- Nothing to patch except the JDK and the base image; the attack surface is what the JDK ships.
- No reflection-based data binding, which removes an entire vulnerability class (polymorphic deserialization).
- We own roughly 2.5k lines of code (comments included) instead of a framework. It is deliberately narrow: it only implements what this service needs.
- `jdk.httpserver` is HTTP/1.1 only and has fewer tuning knobs than Netty/Jetty. TLS and HTTP/2 are delegated to the ingress.
- Adding JUnit later (test scope only) is a reasonable, low-risk exception if the team prefers it; the tests are plain methods and port mechanically.
