// The `mutastic` command: reads a Gradle project's setup, runs the engine, prints the result.
plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(project(":mutastic-engine"))
    implementation(project(":mutastic-report"))
    // Found at run time as BuildTools: the CLI names none of them.
    runtimeOnly(project(":mutastic-gradle"))
    runtimeOnly(project(":mutastic-maven"))
    // Not called, but shipped: the engine starts it as its own process, as a Java agent.
    runtimeOnly(project(":mutastic-worker"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    // Only so ArchUnit sees the annotations module; the CLI never uses it.
    testRuntimeOnly(project(":mutastic-annotations"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    applicationName = "mutastic"
    mainClass = "io.mutastic.cli.Main"
}

tasks.test {
    useJUnitPlatform()
    // The end-to-end tests run mutastic against the sample projects, each its own Gradle build.
    systemProperty("mutastic.samples", rootDir.resolve("samples").absolutePath)
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}
