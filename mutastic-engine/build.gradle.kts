// The engine: finds mutants with the ClassFile API (final in JDK 24), runs the coverage pass and
// each mutant's tests in a worker on the project's own JDK, and reports. Runs on JDK 25.
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api(project(":mutastic-api"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    // The fixtures use mutastic's own annotations as a project would; the engine itself never does.
    testImplementation(project(":mutastic-annotations"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
