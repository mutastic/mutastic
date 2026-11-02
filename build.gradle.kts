// One set of conventions for every module, enforced by the build rather than described: see
// AGENTS.md for the principles each tool here holds the code to.
plugins {
    // Google style, 2 spaces, 100 columns. Pinned to an exact version: a floating formatter makes
    // the diff depend on who ran it last.
    id("com.diffplug.spotless") version "8.10.4"
    id("net.ltgt.errorprone") version "4.1.0" apply false
    base
    jacoco
    // A bill of materials for what mutastic ships, for whoever installs it to check.
    id("org.cyclonedx.bom") version "3.5.1"
    // Maven Central: signing, the POM it requires, and the Central Portal's upload.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    // The Maven plugin's descriptor (plugin.xml), from its goals' annotations.
    id("org.gradlex.maven-plugin-development") version "1.0.3" apply false
    // The Gradle Plugin Portal, for `plugins { id("io.mutastic") }` without adding a repository.
    id("com.gradle.plugin-publish") version "2.2.1" apply false
}

repositories {
    mavenCentral() // for the formatter, which the root runs over the samples
}

subprojects {
    group = "io.mutastic"
    // A release passes its version (`-PreleaseVersion=0.1.0`); every other build is a snapshot.
    version = providers.gradleProperty("releaseVersion").getOrElse("0.2.0-SNAPSHOT")

    repositories {
        mavenCentral()
    }

    // Every warning fails the build: a warning nobody acts on hides the next one.
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Werror", "-Xlint:all"))
    }
    // Javadoc too, for the jars published beside the code. Except doclint's "missing" group, which
    // wants a comment, @param and @return on everything public: comments here are sparse on purpose
    // (AGENTS.md), and a sentence that repeats the signature is noise.
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            addBooleanOption("Xdoclint:all,-missing", true)
            addBooleanOption("Xwerror", true)
            addBooleanOption("quiet", true)
        }
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
                // Set by Maven before a goal runs, as a constructor can't.
                option("NullAway:ExcludedFieldAnnotations", "org.apache.maven.plugins.annotations.Parameter")
                option("NullAway:JSpecifyMode", "true")
            }
        }
    }

    apply(plugin = "jacoco")
    configure<JacocoPluginExtension> {
        toolVersion = "0.8.14"
    }
    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required = true
        }
    }

    apply(plugin = "checkstyle")
    configure<CheckstyleExtension> {
        toolVersion = "14.3.0"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        maxWarnings = 0
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

// Every library is published to Maven Central with the POM it requires. The CLI is an application,
// shipped as a distribution rather than a jar to depend on. Signing uses an in-memory key from
// `signingInMemoryKey`, `signingInMemoryKeyId` and `signingInMemoryKeyPassword` (in CI, secrets as
// ORG_GRADLE_PROJECT_ variables); without one, as on a laptop, publications are left unsigned, and
// Central refuses them. A release is uploaded, then published by hand from the Central Portal:
// until then it can be dropped, and afterwards it never can.
subprojects {
    pluginManager.withPlugin("java-library") {
        apply(plugin = "com.vanniktech.maven.publish")
        // The javadoc jar ships with each release: a doc comment that breaks it fails here, not then.
        tasks.named("check") { dependsOn("javadoc") }
        // A repository in the build, for the end-to-end tests: Maven resolves the plugin from it
        // as it would from Central, and ~/.m2 is left alone.
        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "samples"
                    url = uri(rootProject.layout.buildDirectory.dir("samples-repo"))
                }
            }
        }
        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = false)
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }
            pom {
                name = project.name
                description = provider { project.description }
                url = "https://github.com/mutastic/mutastic"
                inceptionYear = "2026"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id = "briancorbinxyz"
                        name = "Brian Corbin"
                        url = "https://github.com/briancorbinxyz"
                    }
                }
                scm {
                    url = "https://github.com/mutastic/mutastic"
                    connection = "scm:git:https://github.com/mutastic/mutastic.git"
                    developerConnection = "scm:git:ssh://git@github.com/mutastic/mutastic.git"
                }
            }
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

// Every resolved version is written down (`gradle.lockfile`), so a build today and one next year
// get the same jars: `./gradlew lockDependencies --write-locks` after changing a version.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
    tasks.register("lockDependencies") {
        description = "Resolves every configuration, so --write-locks records them all."
        notCompatibleWithConfigurationCache("resolves configurations at execution")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "run with --write-locks" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}

// What ships, not what builds it: the formatter, checkers and test libraries stay out.
allprojects {
    tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
        includeConfigs = listOf("runtimeClasspath")
    }
}

jacoco {
    toolVersion = "0.8.14"
}

// Coverage over every module's tests at once: the CLI's end-to-end tests run the engine in
// process, so a module's own tests undercount it. The worker's runs in the project's JVM, and so
// isn't measured there: 11% of its branches, against 65% overall.
fun JacocoReportBase.overModules() {
    subprojects.filter { it.plugins.hasPlugin("java") }.forEach { module ->
        dependsOn(module.tasks.named("test"))
        val main = module.extensions.getByType<SourceSetContainer>()["main"]
        sourceDirectories.from(main.allJava.srcDirs)
        classDirectories.from(main.output.classesDirs)
        executionData.from(module.layout.buildDirectory.file("jacoco/test.exec"))
    }
}

val coverageReport by tasks.registering(JacocoReport::class) {
    reports {
        xml.required = true
        html.required = true
    }
}

// The floor is today's coverage, on the way to a target of 85%: it rises as coverage does, and a
// change that drops it fails the build. It is never lowered to pass: a branch whose coverage
// depends on the machine gets a test instead.
val coverageCheck by tasks.registering(JacocoCoverageVerification::class) {
    violationRules {
        rule {
            limit {
                counter = "BRANCH"
                minimum = "0.65".toBigDecimal()
            }
        }
    }
}

// Once every module is configured, so their source sets and test tasks exist.
gradle.projectsEvaluated {
    coverageReport { overModules() }
    coverageCheck { overModules() }
}

tasks.check {
    dependsOn(coverageReport, coverageCheck)
}
