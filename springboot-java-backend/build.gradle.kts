plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    jacoco
}

group = "com.example"
version = "1.0.0"

java {
    // 25 is also what the sibling zero-dependency service targets, and the AOT cache
    // used by the container image needs it.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // One BOM governs every version in the build; no version numbers below this line.
    val bom = platform("org.springframework.boot:spring-boot-dependencies:4.1.1")
    implementation(bom)
    annotationProcessor(bom)
    testImplementation(bom)

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Only for /v1/random-strings: Spring MVC streams a Flux returned from a controller as
    // server-sent events on its own, so the reactive types are needed without WebFlux itself.
    implementation("io.projectreactor:reactor-core")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // Arrives transitively with the resource server; declared because the tests mint their own
    // tokens with it rather than mocking the decoder.
    testImplementation("com.nimbusds:nimbus-jose-jwt")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -processing is excluded because annotation processors that generate metadata without
    // claiming their annotations — spring-boot-configuration-processor does exactly that, and it
    // does produce spring-configuration-metadata.json — trip that lint on every compile. The
    // sibling zero-dependency build runs a clean -Xlint:all precisely because it has no processors.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-parameters"))
    // Mirrors STRICT=1 in the sibling module's build.sh: CI turns warnings into errors on the
    // baseline JDK only, so a newer JDK adding a lint category cannot break a local build.
    if (providers.gradleProperty("strict").orNull == "true" || System.getenv("STRICT") == "1") {
        options.compilerArgs.add("-Werror")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
}
