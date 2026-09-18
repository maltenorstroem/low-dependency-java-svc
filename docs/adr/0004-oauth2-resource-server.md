# ADR-0004: OAuth2 resource server with JWT access tokens

- Status: accepted

## Context

ADR-0001 rules out third-party dependencies, and until now the answer to authentication was "put a gateway in front". That is a real answer, but it makes the service unable to say who is calling it, and it means the security posture lives somewhere this repository cannot test. Verifying a JWT needs `java.security`, which is already in `java.base`, so the usual reason to reach for a framework does not apply here.

## Decision

- **Resource server only.** The service verifies access tokens issued by an external identity provider and never issues, refreshes or introspects one.
- **JWKS only, no shared secret.** Keys are fetched from a configured JWKS URL over HTTPS and cached; `http` is accepted only on loopback, so the test suite can run a stub key server. `RS*`, `PS*` and `ES*` are verified; `none` and every `HS*` are refused, and no symmetric key can enter the key table at all.
- **The algorithm comes from the key, not the token.** The `alg` header only has to agree with the key's own algorithm. This makes algorithm confusion structurally impossible rather than merely checked for.
- **Enforcement lives in the dispatcher, driven by route metadata.** `Router` registration takes a mandatory `Access`, so a route whose access level nobody decided does not compile. The dispatcher's single `try/finally` gains authentication as one more thing it guarantees for every exchange, alongside load shedding and the error mapping.
- **Routing runs before authentication.** `OPTIONS` stays public, as does `/health/live`, `/health/ready`, `/metrics` and `/openapi.yaml`.
- **Fail closed.** When the provider is unreachable the last good keys serve until `APP_AUTH_JWKS_MAX_STALE_SECONDS`, then are dropped and every token is refused. Refreshes triggered by an unknown `kid` are rate-limited.
- **Off until an issuer is configured, on the moment one is.** `APP_AUTH_ENABLED` overrides either way, but enabling without a complete configuration is a startup failure.
- **`java.net.http` becomes the third runtime module**, purely to fetch the JWKS.

## Consequences

- The alternative to `java.net.http` was an HTTPS client hand-rolled on `SSLSocket`: several hundred lines of security-sensitive code to avoid about 2 MB in what is now a 42 MB image. ADR-0001 says JDK modules only, not two of them.
- An unauthenticated caller can tell that `/v1/tasks` exists and `/nope` does not. The route table is published at `/openapi.yaml`, so there is nothing there to conceal. The existence of an individual resource is *not* leaked: that 404 comes from the handler, which runs only after authorization.
- `OPTIONS` is public because a CORS preflight cannot carry an `Authorization` header. It reveals only the method set for a template the contract already documents.
- `/openapi.yaml` is public because requiring a token to read the document that explains how to get a token would be circular. `/metrics` is public by deployment choice rather than by protocol, and should be restricted at the ingress.
- The zero-configuration run still works, which is what keeps `./build.sh run` and the container smoke test honest. The cost is that an unsecured deployment is possible; it logs `auth.disabled` at WARN on every boot.
- The service holds no secret of its own — only public keys — so nothing here needs a `Secret` in the deployment manifest.
- Rejections are logged at DEBUG, not WARN: an auth-rejection flood is normal traffic, and logging it at WARN would hand an attacker a log-volume denial of service. `http_server_auth_decisions_total{outcome}` is the alerting surface.
