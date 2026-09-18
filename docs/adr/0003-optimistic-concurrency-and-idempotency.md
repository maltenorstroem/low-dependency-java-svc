# ADR-0003: Mandatory optimistic concurrency, optional idempotency keys

- Status: accepted

## Context

The two classic data-integrity bugs in HTTP APIs are lost updates (two clients overwrite each other) and duplicate creation (a client retries a POST whose response was lost).

## Decision

- Every representation carries a strong `ETag` derived from a monotonically increasing version.
- `PUT` and `DELETE` **require** `If-Match` (428 if missing, 412 if stale). Repositories implement updates as atomic compare-and-set on the version.
- `POST` accepts an `Idempotency-Key`. The same key and payload return the original result; the same key with a different payload returns 422; a concurrent duplicate waits for the first attempt. Keys are bounded in count and time.

## Consequences

- Clients must do read-modify-write with the ETag. `If-Match: *` exists as an explicit opt-out.
- Safe client retries with exponential backoff become possible for all operations.
