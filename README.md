# Zero-dependency Java REST reference backend

A production-shaped REST service in plain Java 21+ with **no third-party dependencies**: not at runtime, not in the build, not in the tests. It uses only `java.base`, `jdk.httpserver` and `java.net.http`, and its container ships a 42 MB `jlink` runtime containing exactly those three modules.

The goal is a service that keeps building and running for many years with no maintenance beyond moving to new JDK LTS releases. It is built on published standards (IETF RFCs, W3C, OpenAPI, Prometheus), so the design stays valid even as frameworks come and go.

## Quick start

```sh
./build.sh all                                   # test + package + jlink (needs only a JDK 21+)
./build.sh run                                   # or: target/image/bin/java -m com.example.app

curl -i -X POST localhost:8080/v1/tasks \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: "3b8f0c1e-order-1"' \
  -d '{"title":"Write docs"}'
# 201 Created, ETag: "1", Location: /v1/tasks/<uuidv7>

curl -i -X PUT localhost:8080/v1/tasks/<id> \
  -H 'Content-Type: application/json' -H 'If-Match: "1"' \
  -d '{"title":"Write better docs","completed":true}'
# 200 OK, ETag: "2"    (stale If-Match -> 412, missing If-Match -> 428)

curl -i -X POST localhost:8080/v1/cubes \
  -H 'Content-Type: application/json' \
  -d '{"description":"Display case","cube":{"length":40,"material":"MATERIALS_GLASS"}}'
# 201 Created, displayName: "Glass cube 40 x 100 x 100"  (omitted fields take the contract's defaults)

curl -so cube.pdf localhost:8080/v1/cubes/<id>/pdf      # a generated one-page data sheet
```

Two resources are implemented. `tasks` is the minimal flat example; `cubes` is the same machinery
applied to a nested entity taken from the `lgt.polaris.cube` Protocol Buffers contract, with a
non-JSON representation (PDF) on the side.

With Docker: `docker build -t task-service . && docker run -p 8080:8080 --read-only --tmpfs /tmp task-service`

## Standards used

| Concern | Standard | Where |
|---|---|---|
| HTTP semantics, status codes, conditional requests | RFC 9110 | `Router`, `EntityTags`, `Dispatcher` |
| Error format | RFC 9457 Problem Details | `Problems` |
| JSON | RFC 8259 + I-JSON (RFC 7493) | `Json` |
| Validation error locations | JSON Pointer (RFC 6901) | `TaskJson`, `CubeJson` (nested) |
| Identifiers | UUIDv7 (RFC 9562) | `UuidV7` |
| Timestamps | RFC 3339 (UTC) | `Json` writer |
| Pagination links | Web Linking (RFC 8288) | `TaskApi.list`, `CubeApi.list` |
| Precondition required | RFC 6585 (428) | `EntityTags` |
| Safe POST retries | IETF `Idempotency-Key` header draft | `IdempotencyStore` |
| Tracing correlation | W3C Trace Context (`traceparent`) | `Dispatcher` |
| API contract | OpenAPI 3.1 | `src/main/resources/openapi.yaml`, served at `/openapi.yaml` |
| Document rendering | PDF 1.7 (ISO 32000-1) | `CubePdf` |
| Metrics | Prometheus text exposition format | `/metrics` |
| Logging | JSON lines on stdout (12-factor), JDK `System.Logger` (JEP 264) | `Log` |
| Configuration | Environment variables (12-factor) | `Config` |
| Health | Kubernetes liveness/readiness convention | `/health/live`, `/health/ready` |
| Security headers | OWASP REST Security Cheat Sheet | `Dispatcher` |
| Modularity / packaging | JPMS + `jlink` | `module-info.java`, `build.sh` |
| Architecture records | ADRs | `docs/adr` |

## What makes it bulletproof

**Every input is bounded.** Body size is capped and checked before buffering (413). JSON nesting depth and number length are capped, so hostile input fails fast instead of overflowing the stack. Header count, request/response time, idle connections and total connections are capped via documented `jdk.httpserver` properties. Every in-memory structure (task store, cube store, idempotency keys) has a hard limit.

**Every input is parsed strictly.** The parser rejects duplicate JSON keys, unpaired surrogates, invalid UTF-8, unknown fields, repeated query parameters, unknown query parameters, non-canonical UUIDs and wrong media types. It never guesses what the client meant. Titles are trimmed and NFC-normalized, so equal-looking strings compare equal.

**Overload is shed, not queued.** A semaphore caps concurrent requests; excess requests get an immediate `503` with `Retry-After` instead of piling up until memory runs out. Handlers run on virtual threads, so blocking code is cheap.

**Writes cannot be lost or doubled.** `PUT` and `DELETE` require `If-Match`, and updates are atomic compare-and-set operations, which rules out lost updates (tested with 32 concurrent writers). `POST` accepts an `Idempotency-Key`, so a client that times out can safely retry. A concurrent duplicate waits for the first request and returns the same result.

**Failure handling is centralized and total.** One `Dispatcher` handles every exchange through a single `try/finally`. That guarantees a request id, a problem response, a metric, an access log line, and a closed exchange for every request, whatever happens. Domain failures form a *sealed* hierarchy, and the status mapping is an exhaustive `switch`. If you add a new failure type and forget to map it, the code does not compile. Unexpected errors return a generic 500 with the request id and never leak internals.

**Immutability by default.** Domain objects and configuration are records validated in their constructors, so an invalid `Task`, `Cube`, `Colour` or `Config` cannot exist.

**Lifecycle is explicit.** Configuration is validated at startup (exit code 2 on error). On `SIGTERM` the service reports not-ready, waits for load balancers to notice, stops accepting connections, lets in-flight requests finish, and exits. The JVM exits on `OutOfMemoryError` (crash-only design). The container runs as non-root on a read-only filesystem.

**No hidden behaviour.** There is no reflection, no annotations, no classpath scanning, and no dependency injection container. The wiring is 30 lines of plain Java in `Application`.

## Layout

```
src/main/java/com/example/app/
  Main, Application     entry point and composition root
  config/               environment configuration
  domain/               Task and Cube aggregates, services, repository ports + in-memory
                        adapters, generic idempotency store
  api/                  HTTP adapters for tasks and cubes, JSON mapping, PDF rendering
  http/                 router, dispatcher, request/response, problem details, ETags, route access
  security/             OAuth2 resource server: JWT verification, JWKS cache, scope checks
  json/                 strict JSON parser/writer
  observability/        JSON logging, Prometheus metrics
src/test/java/          105 tests incl. black-box HTTP tests, a 100-line test runner
deploy/kubernetes.yaml  hardened Deployment with probes
docs/adr/               architecture decision records
```

Dependencies point inward (`api`/`http` → `domain`). The domain knows nothing about HTTP or JSON.

## Configuration

| Variable | Default | Range |
|---|---|---|
| `APP_HOST` | `0.0.0.0` | |
| `APP_PORT` | `8080` | 0–65535 (0 = ephemeral) |
| `APP_MAX_CONNECTIONS` | `1000` | |
| `APP_MAX_CONCURRENT_REQUESTS` | `256` | beyond this: 503 |
| `APP_MAX_BODY_BYTES` | `1048576` | 1 KiB – 64 MiB |
| `APP_REQUEST_TIMEOUT_SECONDS` | `30` | read and write timeout |
| `APP_DRAIN_DELAY_SECONDS` | `5` | not-ready period before closing the listener |
| `APP_SHUTDOWN_GRACE_SECONDS` | `20` | time allowed for in-flight requests |
| `APP_LOG_LEVEL` | `INFO` | TRACE, DEBUG, INFO, WARNING, ERROR, OFF |
| `APP_MAX_TASKS` | `100000` | store capacity (507 when full) |
| `APP_MAX_CUBES` | `100000` | store capacity (507 when full) |
| `APP_IDEMPOTENCY_TTL_SECONDS` | `86400` | |
| `APP_MAX_IDEMPOTENCY_KEYS` | `10000` | |
| `APP_AUTH_ISSUER` | _(unset)_ | setting it enables authentication; must equal the token's `iss` exactly |
| `APP_AUTH_ENABLED` | issuer is set | explicit override, either way |
| `APP_AUTH_JWKS_URL` | _(unset)_ | required when enabled; `https`, or `http` on loopback |
| `APP_AUTH_AUDIENCE` | _(unset)_ | when set, the token's `aud` must contain it |
| `APP_AUTH_SCOPE_PREFIX` | `task-service:` | prepended to `tasks:read` and friends |
| `APP_AUTH_REALM` | `api` | the `realm` in `WWW-Authenticate` |
| `APP_AUTH_JWKS_TTL_SECONDS` | `300` | 10 - 86400 |
| `APP_AUTH_JWKS_MIN_REFRESH_SECONDS` | `30` | 1 - 3600; rate-limits refreshes on an unknown `kid` |
| `APP_AUTH_JWKS_MAX_STALE_SECONDS` | `3600` | 60 - 86400; past this, cached keys are dropped |
| `APP_AUTH_JWKS_TIMEOUT_SECONDS` | `5` | 1 - 60 |
| `APP_AUTH_JWKS_MAX_BYTES` | `131072` | 1 KiB - 1 MiB |
| `APP_AUTH_CLOCK_LEEWAY_SECONDS` | `60` | 0 - 300; applied to `exp`, `nbf` and `iat` |
| `APP_AUTH_MAX_TOKEN_BYTES` | `8192` | 256 - 65536 |

## Authentication

The service is an **OAuth2 resource server**: it verifies JWT access tokens issued by an external
identity provider and never issues one itself. Point it at a provider and it enforces; give it
nothing and it starts unauthenticated, logging `auth.disabled` at WARN on every boot.

```sh
APP_AUTH_ISSUER=https://idp.example.com/realms/demo \
APP_AUTH_AUDIENCE=task-service \
APP_AUTH_JWKS_URL=https://idp.example.com/realms/demo/protocol/openid-connect/certs \
./build.sh run
```

Setting `APP_AUTH_ENABLED=true` without an issuer and a JWKS URL is a startup failure (exit 2),
not a silent bypass.

**Scopes.** Every route declares what it needs, and the `Router` has no registration method that
omits it, so a new route that nobody decided the access level for does not compile. `GET` and
`HEAD` need `:read`, `POST`/`PUT`/`DELETE` need `:write`; the `APP_AUTH_SCOPE_PREFIX` exists
because scope names live in the identity provider's namespace, where a bare `tasks:read` would
collide with every other service in a shared realm.

| Route | Scope |
|---|---|
| `GET`, `HEAD /v1/tasks[/{id}]` | `task-service:tasks:read` |
| `POST`, `PUT`, `DELETE /v1/tasks[/{id}]` | `task-service:tasks:write` |
| `GET`, `HEAD /v1/cubes[/{id}]`, `/v1/cubes/{id}/pdf` | `task-service:cubes:read` |
| `POST`, `PUT`, `DELETE /v1/cubes[/{id}]` | `task-service:cubes:write` |
| `/health/live`, `/health/ready`, `/metrics`, `/openapi.yaml` | none |

**Responses** follow RFC 6750 and RFC 9457: `401` with `WWW-Authenticate: Bearer realm="..."` and
no error code when no credentials were sent, `401 invalid_token` when one was and it did not hold
up, `403 insufficient_scope` with the required `scope` when it did but fell short, and `503` with
`Retry-After` when the identity provider is unreachable — that last one is our failure, not the
caller's, and a client with a perfectly good token should retry rather than go and get another.
Only "expired" and "malformed" are distinguished in the description; every other cause collapses
to one message, so the endpoint cannot be used to probe which part of a forged token was wrong.

**Verification** is `RS256/384/512`, `PS256/384/512` and `ES256/384/512` against keys fetched from
the JWKS URL and cached. `none` and every `HS*` are refused, and the algorithm used comes from the
key rather than the token header, so the classic confusion attack — re-signing with HMAC keyed on
the RSA public key anyone can fetch — has nothing to land on. The key set refreshes on an unknown
`kid` so a rotation needs no restart, rate-limited so a flood of invented key ids cannot turn this
service into an amplifier aimed at the provider. When the provider is down the last good keys keep
working for `APP_AUTH_JWKS_MAX_STALE_SECONDS` and then are dropped: it fails closed.

The token is never logged, never echoed and never becomes a metric label. The access log carries
`sub`, and `http_server_auth_decisions_total{outcome}` is the thing to alert on.

**Still out of scope**: issuing tokens, refresh and introspection (RFC 7662), mTLS-bound tokens
(RFC 8705), and per-tenant authorization inside the domain. `Request.principal()` is where the
last of those would start.

## Deliberately out of scope

These concerns belong in the platform or need a decision this reference cannot make for you:

- **TLS and HTTP/2** belong at the ingress or service mesh. (`HttpsServer` exists in the JDK if you need TLS in-process.)
- **Persistence**: implement `TaskRepository` / `CubeRepository` with JDBC (`java.sql` is in the JDK; only the database driver is an external artifact). Use the version column for the compare-and-set. Until then, keep `replicas: 1`.
- **`/metrics` is public** and should be restricted to the cluster network at the ingress. The probes and the OpenAPI document are public by protocol; this one is public by deployment choice.
- **CORS, per-client rate limiting, `PATCH`** (JSON Merge Patch, RFC 7396) can each be added in one class.

## Long-term maintenance

- **Runtime**: move to each new JDK LTS. CI tests the baseline (21) and the current LTS (25), and a monthly scheduled run catches breakage early.
- **Moving parts**: the only things that change are two base images and two CI actions, all tracked by Dependabot.
- **Base images**: Red Hat UBI 10 for both stages — `eclipse-temurin:25-jdk-ubi10-minimal` to build, `ubi10/ubi-micro` to run. `ubi-micro` is glibc and nothing else (no package manager, no shell, no coreutils), so a scan of the runtime image reports no OS packages beyond the C library, and Red Hat ships security errata plus VEX data for what remains. Both stages must stay on the same UBI major: the `jlink` image contains native code linked against the build stage's glibc.
- **Source**: compiled with `--release 21`. Warnings fail the build only on the baseline JDK (`STRICT=1`), so a newer `javac` adding lint categories cannot break your build.
- **Framework**: `jdk.httpserver` is an exported, supported JDK module that has been in every JDK since Java 6.
- **Known limitation**: requests the JDK server rejects before reaching the application (for example, a malformed request line) get the JDK's plain HTML `400`, not a problem document.

## Why REST and not gRPC

See [ADR-0002](docs/adr/0002-rest-over-grpc.md). In short, gRPC in Java requires `grpc-java`, `protobuf-java`, Netty and Guava. Those are large, fast-moving dependencies with a steady CVE stream, which is the opposite of "no maintenance". If you need gRPC for internal clients, place a gRPC–JSON gateway (Envoy's transcoder) in front of this service, or add a separate gRPC adapter module that calls the same `TaskService`.
