# ADR-0002: REST/JSON over gRPC

- Status: accepted

## Context

Both REST and gRPC are industry standards. The overriding requirement is longevity with near-zero dependencies.

## Decision

Expose REST with JSON, described by OpenAPI 3.1, with errors per RFC 9457.

## Consequences

- gRPC in Java cannot be done without `grpc-java`, `protobuf-java`, a transport (Netty or OkHttp), Guava and code generation plugins in the build; all are large and release frequently.
- REST is callable from anything (curl, browsers, every language) without generated stubs, which helps a reference implementation stay useful.
- Where gRPC is required, add a transcoding proxy (e.g. Envoy gRPC-JSON transcoder) or a separate adapter module over the same `TaskService`; the domain layer does not change.
