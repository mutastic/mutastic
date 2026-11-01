plugins {
    // Lets Gradle download the JDKs: 25 for the engine, 17 for building and testing the worker.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mutastic"

include(
    "mutastic-api",
    "mutastic-worker",
    "mutastic-engine",
    "mutastic-report",
    "mutastic-gradle",
    "mutastic-maven-extension",
    "mutastic-maven",
    "mutastic-cli",
)
