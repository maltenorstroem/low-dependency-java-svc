# ADR-0005: A second implementation on Spring Boot

- Status: accepted

## Context

ADR-0001 rules out third-party dependencies. That decision was made to minimise long-term maintenance and attack surface, and it stands for the reference implementation.

It does not answer the question every reviewer actually asks: what would this service cost, and what would it buy, if it were built the way most Java shops build? The existing answer is an assertion in a README. An assertion is weaker than a measurement, and it ages badly — "a framework would be heavier" is not a number, and nobody can check it.

There is also a narrower risk. A codebase that has written its own router, JSON parser, metrics renderer and JWT verifier has no way to tell whether those pieces are genuinely small and sufficient, or merely familiar. Building the same contract a second way is how that gets tested.

## Decision

Add a second, independent implementation of the same OpenAPI contract in `springboot-java-backend/`, built on Spring Boot the idiomatic way, and keep both green in CI against the same smoke script.

**ADR-0001's scope is hereby narrowed to the root module.** The Spring Boot module is explicitly exempt from it. It is expected to acquire dependencies, Dependabot traffic, a larger image and a slower start; those are the measurements, not failures.

Where the two disagree, the zero-dependency module is normative and `openapi.yaml` is the shared source of truth. Both copies of that file are checked byte-identical in CI, as is the `domain` package, which ports between the two unchanged.

The differences that remain are listed in `springboot-java-backend/README.md`. They are deliberate: the port takes Spring's defaults wherever a default exists, because a port that fought the framework at every turn would measure the fight rather than the framework.

## Consequences

- Two build systems, two Dockerfiles, two deployment manifests, two test suites. Everything is done twice, on purpose.
- Contract drift is the real risk. It is mitigated by `scripts/smoke.sh`, which runs against both images in CI, and by the two identity checks on `openapi.yaml` and `domain`.
- Some behaviour is not reproduced exactly. Metric names are Micrometer's, so dashboards do not carry over. Jackson reports the first structural error in a body rather than all of them. These are recorded rather than worked around.
- The measurements, as of this ADR: roughly 3,900 lines of code against roughly 2,800 (of which 674 are the shared domain); 68.5 MB against 191 MB; about 60 ms to start against about 1.9 s with an AOT cache; 0 direct dependencies against 6 starters resolving to 81 artifacts.
- The comparison only stays honest while both are maintained. If one is allowed to rot, delete it rather than leave a misleading benchmark in the repository.
