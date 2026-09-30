plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    // The generator reads the same YAML the application reads at runtime.
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.3")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Run these with `./gradlew -p buildSrc test`.
//
// They are not reachable from the main build's `check`. Gradle 8 asks buildSrc only for its jar, and
// a `GradleBuild` task cannot run them either, because a nested build rooted at a directory called
// `buildSrc` is rejected as a reserved name. So CI's backend job runs them as a step of their own
// (#81), because the ontology lint's rules are tested only here; the generator is also guarded
// behaviourally, by `ontologyDriftCheck` and the ontology-codegen acceptance scenarios.
tasks.test {
    useJUnitPlatform()
}
