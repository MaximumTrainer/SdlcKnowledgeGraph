plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    `jvm-test-suite`
}

group = "com.repodatagraph"
version = "0.0.1-SNAPSHOT"

// META-INF/build-info.properties, so /actuator/info can report the version it was built as (#48, D2).
// Without the time, so an unchanged build stays up to date instead of rebuilding for a timestamp.
springBoot {
    buildInfo {
        excludes.set(setOf("time"))
    }
}

val cucumberVersion = "7.20.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // /actuator/prometheus (#44, FR7). Only the endpoint needs it; the application meters through Micrometer.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // One JSON object per log line under the docker profile (#44), so a platform can aggregate the logs.
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")
    implementation("org.springframework.boot:spring-boot-starter-data-neo4j")
    implementation("org.springframework.boot:spring-boot-starter-graphql")
    // The API as an OAuth 2 resource server: bearer JWTs from a configured issuer (#114, ADR-0005).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    // The ontology registry is YAML on the classpath, read at startup.
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Unit-test suite only. Pact lives in the contractTest suite; Testcontainers and Cucumber in
    // the integrationTest and acceptanceTest suites (see the `testing { }` block below).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.springframework.graphql:spring-graphql-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

ktlint {
    version.set("1.3.1")
    android.set(false)
    // Generated from the event registry and drift-checked byte for byte (#44): formatting it would
    // make it differ from what the generator writes.
    filter { exclude("**/observability/LogEvents.kt") }
    reporters {
        // PLAIN for a readable console failure, CHECKSTYLE for CI to render as annotations.
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

// detekt 1.23.7 embeds Kotlin 2.0.10 and refuses to run against a different compiler version.
// Pin the Kotlin artifacts on detekt's own classpath; this does not affect how the project compiles.
// See https://detekt.dev/docs/gettingstarted/gradle#dependencies
configurations.matching { it.name == "detekt" }.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion("2.0.10")
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("config/detekt/detekt.yml"))
    baseline = file("config/detekt/baseline.xml")
    source.setFrom(files("src"))
}

// The generated event functions are one-liners by construction, and checked against the registry
// rather than against style rules.
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach { exclude("**/observability/LogEvents.kt") }

// detekt runs its own embedded Kotlin compiler and refuses to start when the Kotlin on *its*
// classpath is not the one it was built against:
//
//     detekt was compiled with Kotlin 2.0.21 but is currently running with 2.0.10
//
// Spring's dependency-management plugin manages every org.jetbrains.kotlin artifact to the version it
// sees for the project, and that reaches detekt's classpath too, where it has no business. Pinning
// detekt's own Kotlin keeps the two independent, so a Kotlin upgrade is no longer silently a detekt
// upgrade as well, and the other way round (#138).
//
// The value is detekt's, not the project's. It changes when detekt does.
val detektKotlinVersion = "2.0.21"

configurations.matching { it.name.startsWith("detekt") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion(detektKotlinVersion)
    }
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        // SARIF so CI can surface findings inline on the pull request.
        sarif.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
    }
}

/**
 * The ontology registry is the single declaration of the model; these tasks render it into the
 * forms other layers would otherwise hand-write. Generated files are committed so an ontology change
 * shows its full effect in the diff, and `ontologyDriftCheck` makes it impossible to commit the
 * registry change without them.
 */
val ontologyDir = layout.projectDirectory.dir("src/main/resources/ontology/v1")
val generatedSdl = layout.projectDirectory.file("src/main/resources/graphql/schema.generated.graphqls")
val generatedTs = layout.projectDirectory.file("../frontend/src/generated/ontology.ts")
val generatedJson = layout.projectDirectory.file("src/main/resources/ontology/v1/ontology.json")

val generateOntology by tasks.registering(OntologyCodegenTask::class) {
    group = "ontology"
    description = "Generates GraphQL SDL, TypeScript types and the ontology JSON fixture from the registry"
    ontologyDirectory.set(ontologyDir)
    graphqlOutput.set(generatedSdl)
    typescriptOutput.set(generatedTs)
    jsonOutput.set(generatedJson)
}

val ontologyDriftCheck by tasks.registering(OntologyDriftCheckTask::class) {
    group = "verification"
    description = "Fails when the committed generated ontology files no longer match the registry"
    ontologyDirectory.set(ontologyDir)
    graphqlOutput.set(generatedSdl)
    typescriptOutput.set(generatedTs)
    jsonOutput.set(generatedJson)
}

tasks.named("check") { dependsOn(ontologyDriftCheck) }

/**
 * The log event registry (#44) is the single declaration of what the application may log; these
 * render it into the only way to log (`LogEvents.kt`) and the JSON the website shows. Committed and
 * drift-checked like the ontology's outputs.
 */
val eventRegistry = layout.projectDirectory.file("src/main/resources/observability/events.yaml")
val generatedLogEvents = layout.projectDirectory.file("src/main/kotlin/com/repodatagraph/observability/LogEvents.kt")
val generatedEventsJson = layout.projectDirectory.file("src/main/resources/observability/events.json")

val generateLogEvents by tasks.registering(LogEventCodegenTask::class) {
    group = "observability"
    description = "Generates LogEvents.kt and events.json from the log event registry"
    registryFile.set(eventRegistry)
    kotlinOutput.set(generatedLogEvents)
    jsonOutput.set(generatedEventsJson)
}

val logEventDriftCheck by tasks.registering(LogEventDriftCheckTask::class) {
    group = "verification"
    description = "Fails when the committed LogEvents.kt or events.json no longer match the registry"
    registryFile.set(eventRegistry)
    kotlinOutput.set(generatedLogEvents)
    jsonOutput.set(generatedEventsJson)
}

tasks.named("check") { dependsOn(logEventDriftCheck) }

// `src/testSupport/kotlin` holds helpers shared by more than one suite (currently the Testcontainers
// Neo4j configuration used by both integrationTest and acceptanceTest). The Kotlin plugin compiles
// `.kt` files found in a source set's java source directories, so adding the directory is enough.
val sharedTestSupport = "src/testSupport/kotlin"

/**
 * Four suites, so that git hooks can run a fast gate on commit and the full gate on push
 * (see lefthook.yml and docs/TESTING.md):
 *   test           unit tests only, no Spring context and no Docker
 *   integrationTest Spring slices and adapters against a real Neo4j (Testcontainers)
 *   acceptanceTest  Gherkin features driving the running application, outside-in
 *   contractTest    Pact provider verification against the pacts in contracts/pacts
 */
testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
        }

        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter()
            sources { java.srcDir(sharedTestSupport) }
            dependencies {
                // `project()` contributes the project's classes but not its implementation
                // dependencies, so the suites restate the parts of the runtime they compile against.
                implementation(project())
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation("org.springframework.boot:spring-boot-starter-data-neo4j")
                // TestPrincipalConfig in src/testSupport replaces the API's JWT decoder (#118).
                implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
                implementation("org.testcontainers:junit-jupiter")
                implementation("org.testcontainers:neo4j")
                // A fake GitHub, so the connector is driven against real HTTP - pagination, rate
                // limit headers and all - rather than against a mocked client that cannot get those
                // wrong. Standalone: it brings its own shaded Jetty and does not fight Boot's. On
                // every suite that compiles src/testSupport, because FakeGitHub lives there.
                implementation("org.wiremock:wiremock-standalone:3.13.2")
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(test)
                    // Gradle's -D lands on the daemon, not on the forked test JVM, so
                    // `./gradlew integrationTest -DupdateOpenApi=true` needs this passthrough to
                    // reach OpenApiExportTest.
                    systemProperty("updateOpenApi", providers.systemProperty("updateOpenApi").getOrElse("false"))
                }
            }
        }

        val acceptanceTest by registering(JvmTestSuite::class) {
            useJUnitJupiter()
            sources { java.srcDir(sharedTestSupport) }
            dependencies {
                implementation(project())
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation("org.springframework.boot:spring-boot-starter-data-neo4j")
                // TestPrincipalConfig in src/testSupport replaces the API's JWT decoder (#118).
                implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
                implementation("org.testcontainers:junit-jupiter")
                implementation("org.testcontainers:neo4j")
                // A fake GitHub, so the connector is driven against real HTTP - pagination, rate
                // limit headers and all - rather than against a mocked client that cannot get those
                // wrong. Standalone: it brings its own shaded Jetty and does not fight Boot's. On
                // every suite that compiles src/testSupport, because FakeGitHub lives there.
                implementation("org.wiremock:wiremock-standalone:3.13.2")
                implementation("io.cucumber:cucumber-java:$cucumberVersion")
                implementation("io.cucumber:cucumber-spring:$cucumberVersion")
                implementation("io.cucumber:cucumber-junit-platform-engine:$cucumberVersion")
                implementation("org.junit.platform:junit-platform-suite")
            }
            targets.all { testTask.configure { shouldRunAfter(test, integrationTest) } }
        }

        val contractTest by registering(JvmTestSuite::class) {
            useJUnitJupiter()
            sources { java.srcDir(sharedTestSupport) }
            dependencies {
                implementation(project())
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation("org.springframework.boot:spring-boot-starter-data-neo4j")
                // TestPrincipalConfig in src/testSupport replaces the API's JWT decoder (#118).
                implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
                implementation("org.testcontainers:junit-jupiter")
                implementation("org.testcontainers:neo4j")
                // A fake GitHub, so the connector is driven against real HTTP - pagination, rate
                // limit headers and all - rather than against a mocked client that cannot get those
                // wrong. Standalone: it brings its own shaded Jetty and does not fight Boot's. On
                // every suite that compiles src/testSupport, because FakeGitHub lives there.
                implementation("org.wiremock:wiremock-standalone:3.13.2")
                implementation("au.com.dius.pact.provider:junit5spring:4.6.21")
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(test)
                    // No broker (ADR-0004), so nothing is published back; verification is local only.
                    systemProperty("pact.verifier.publishResults", "false")
                }
            }
        }
    }
}

// `./gradlew check` (and therefore the pre-push hook and CI) runs every suite.
tasks.named("check") {
    dependsOn(
        testing.suites.named("integrationTest"),
        testing.suites.named("acceptanceTest"),
        testing.suites.named("contractTest"),
    )
}
