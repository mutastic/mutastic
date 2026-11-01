// One set of conventions for every module, enforced by the build rather than described: see
// AGENTS.md for the principles each tool here holds the code to.
plugins {
    // Google style, 2 spaces, 100 columns. Pinned to an exact version: a floating formatter makes
    // the diff depend on who ran it last.
    id("com.diffplug.spotless") version "8.10.4"
    id("net.ltgt.errorprone") version "4.1.0" apply false
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

    // Every warning fails the build: a warning nobody acts on hides the next one.
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Werror", "-Xlint:all"))
    }

    // Correctness you didn't think to check.
    apply(plugin = "net.ltgt.errorprone")
    dependencies {
        "errorprone"("com.google.errorprone:error_prone_core:2.50.0")
        // Null is a compile error in packages marked @NullMarked (JSpecify), adopted one at a time.
        "errorprone"("com.uber.nullaway:nullaway:0.14.2")
    }
    pluginManager.withPlugin("java") {
        dependencies {
            // Annotations only: the worker runs inside the project's JVM, which needn't have them.
            "compileOnly"("org.jspecify:jspecify:1.0.1")
            "testCompileOnly"("org.jspecify:jspecify:1.0.1")
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        (options as ExtensionAware).extensions.configure<net.ltgt.gradle.errorprone.ErrorProneOptions> {
            // Off by default in Error Prone, on here because each holds one of AGENTS.md's
            // conventions. Each was tried alone over the whole tree first: none found anything,
            // because the code was written to the conventions before the build checked them. They
            // stay as guards, not clean-ups, against code written after.
            error(
                "Varifier", // 11: `var` when the right-hand side names the type
                "IdentifierName", // 11: names read as words
                "ConstantPatternCompile", // a Pattern is compiled once, and named
                "ImmutableSetForContains",
                "StatementSwitchToExpressionSwitch",
                "MissingDefault",
                "RedundantOverride",
                "UnnecessaryStaticImport",
            )
            // Production code only: in a test, a null where a value belongs fails the test anyway.
            if (name.contains("Test")) {
                disable("NullAway")
            } else {
                error("NullAway")
                option("NullAway:OnlyNullMarked", "true")
                option("NullAway:JSpecifyMode", "true")
            }
        }
    }

    apply(plugin = "checkstyle")
    configure<CheckstyleExtension> {
        toolVersion = "14.3.0"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        isIgnoreFailures = true
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
