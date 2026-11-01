plugins {
    // Lets Gradle download the JDKs: 25 for the engine, 17 for building and testing the worker.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mutastic"

include("mutastic-worker", "mutastic-engine", "mutastic-cli")
