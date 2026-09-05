plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    // The generator reads the same YAML the application reads at runtime.
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// buildSrc is built before the project it builds, so these tests run on every Gradle invocation,
// including CI's `./gradlew check`. A broken generator fails the build before anything uses it.
tasks.test {
    useJUnitPlatform()
}
