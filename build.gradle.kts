// One set of conventions for every module, enforced by the build rather than described: see
// AGENTS.md for the principles each tool here holds the code to.
plugins {
    // Google style, 2 spaces, 100 columns. Pinned to an exact version: a floating formatter makes
    // the diff depend on who ran it last.
    id("com.diffplug.spotless") version "8.10.4"
}

repositories {
    mavenCentral() // for the formatter, which the root runs over the samples
}

subprojects {
    group = "io.mutastic"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    apply(plugin = "com.diffplug.spotless")
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat("1.36.1") // 1.35 turns a wrapped `///` comment into a dangling `//`
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint()
        }
    }
}

// The samples are their own builds, but their code is quoted in the README and the tests: it's
// held to the same layout.
spotless {
    java {
        target("samples/*/src/**/*.java")
        googleJavaFormat("1.36.1")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "samples/*/*.gradle.kts")
        ktlint()
    }
}
