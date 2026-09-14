plugins {
    // Auto-provisions a JDK matching the configured Java toolchain (17) when none is installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "repodatagraph"
