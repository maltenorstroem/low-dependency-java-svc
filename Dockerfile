# syntax=docker/dockerfile:1
#
# Two stages: build + test with a full JDK, then ship only a jlink'ed runtime
# (java.base + jdk.httpserver, ~40 MB) on Red Hat UBI as a non-root user.
#
# Both stages sit on UBI 10 on purpose. The jlink'ed image contains native code
# (libjvm.so and friends) linked against the build stage's glibc, so build and
# runtime must share a C library. Mixing a Debian/Ubuntu-based JDK with a UBI
# runtime is what produces "version `GLIBC_2.x' not found" at container start.
#
# ubi-micro carries glibc and nothing else: no package manager, no shell, no
# coreutils, so there is almost no non-JDK attack surface left to report a CVE
# against. Red Hat ships security errata for UBI content and publishes its own
# OVAL/VEX feeds, which is what scanners need to mark unreachable CVEs as
# not-affected instead of flagging them.
#
# For reproducible builds pin both images by digest: image:tag@sha256:...

ARG JDK_IMAGE=docker.io/library/eclipse-temurin:25-jdk-ubi10-minimal
ARG RUNTIME_IMAGE=registry.access.redhat.com/ubi10/ubi-micro:latest

FROM ${JDK_IMAGE} AS build
# ubi-minimal ships no findutils; build.sh uses find(1) to collect sources.
RUN microdnf install -y findutils && microdnf clean all
WORKDIR /src
COPY build.sh ./
COPY src ./src
RUN STRICT=0 ./build.sh all

FROM ${RUNTIME_IMAGE}
COPY --from=build /src/target/image /opt/app
# Numeric UID: no passwd entry needed, satisfies Kubernetes runAsNonRoot.
USER 65532:65532
ENV APP_PORT=8080
EXPOSE 8080
STOPSIGNAL SIGTERM
# Exec form: the JVM is PID 1 and receives SIGTERM directly, triggering graceful shutdown.
# ExitOnOutOfMemoryError: crash-only design; a clean restart beats limping along.
ENTRYPOINT ["/opt/app/bin/java", \
    "-XX:MaxRAMPercentage=75", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-XX:-UsePerfData", \
    "-m", "com.example.app"]
