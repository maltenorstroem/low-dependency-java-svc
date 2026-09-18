# syntax=docker/dockerfile:1
#
# Five stages: build with a full JDK, extract the layers, jlink a runtime, train
# an AOT cache on that runtime, then ship both on ubi-micro as a non-root user.
#
# The sibling zero-dependency service jlinks java.base + jdk.httpserver into a
# ~40 MB image. A Spring Boot fat jar is a class path rather than a module graph,
# so it cannot be reduced that far: it needs a full SE runtime, and the AOT cache
# is itself tens of megabytes. Both images are built the same way — jlink'ed,
# on ubi-micro — precisely so the size difference that remains is the framework
# and not a different choice of base image.
#
# The AOT cache is what buys the startup back, roughly halving it. The training
# run starts the application context, writes what it loaded, and exits without
# ever serving a request; the runtime stage then reads that cache instead of
# rediscovering the same classes on every boot. It is a Java 25 mechanism
# (-XX:AOTCacheOutput and -XX:AOTCache) and supersedes the older AppCDS
# -XX:ArchiveClassesAtExit. The cache is tied to the runtime that produced it,
# which is why training runs on the jlink'ed image and not on the JDK.
#
# Both stages sit on UBI 10 on purpose. The jlink'ed runtime contains native code
# linked against the build stage's glibc, so build and runtime must share a C
# library; mixing a Debian-based JDK with a UBI runtime is what produces
# "version `GLIBC_2.x' not found" at container start.
#
# For reproducible builds pin both images by digest: image:tag@sha256:...

ARG JDK_IMAGE=docker.io/library/eclipse-temurin:25-jdk-ubi10-minimal
ARG RUNTIME_IMAGE=registry.access.redhat.com/ubi10/ubi-micro:latest

FROM ${JDK_IMAGE} AS build
WORKDIR /src
# Dependencies first, so editing a source file does not re-resolve them.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

FROM ${JDK_IMAGE} AS runtime-image
# java.se rather than a jdeps-derived list: Spring resolves a great deal
# reflectively, so a minimal set computed from the bytecode is not trustworthy.
# jdk.unsupported is needed for sun.misc.Unsafe, which the object libraries still
# reach for; jdk.crypto.ec supplies the EC curves used to verify ES* tokens.
RUN jlink \
      --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.management,jdk.jfr \
      --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
      --output /javaruntime

FROM ${JDK_IMAGE} AS extract
WORKDIR /builder
COPY --from=build /src/build/libs/*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM ${RUNTIME_IMAGE} AS training
ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=runtime-image /javaruntime ${JAVA_HOME}
WORKDIR /application
COPY --from=extract /builder/extracted/dependencies/ ./
COPY --from=extract /builder/extracted/spring-boot-loader/ ./
COPY --from=extract /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract /builder/extracted/application/ ./
# spring.context.exit=onRefresh stops once the context is built, so nothing binds
# a port and no APP_AUTH_* is read: the training run never contacts an identity
# provider or anything else on the network.
RUN java -XX:AOTCacheOutput=application.aot -Dspring.context.exit=onRefresh -jar application.jar

FROM ${RUNTIME_IMAGE}
ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=runtime-image /javaruntime ${JAVA_HOME}
WORKDIR /application
COPY --from=training /application/ ./
# Numeric UID: no passwd entry needed, satisfies Kubernetes runAsNonRoot.
USER 65532:65532
ENV APP_PORT=8080
EXPOSE 8080
STOPSIGNAL SIGTERM
# Exec form: the JVM is PID 1 and receives SIGTERM directly, triggering graceful shutdown.
# ExitOnOutOfMemoryError: crash-only design; a clean restart beats limping along.
ENTRYPOINT ["java", \
    "-XX:AOTCache=application.aot", \
    "-XX:MaxRAMPercentage=75", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-XX:-UsePerfData", \
    "-jar", "application.jar"]
