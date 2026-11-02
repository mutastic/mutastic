// Maven builds: runs `mvn test-compile` with mutastic's extension loaded, which writes what
// Surefire would run. Found by the CLI as a BuildTool.
plugins {
    `java-library`
}

description = "Reads a Maven build's test setup for mutastic."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api(project(":mutastic-api"))
    // Not called here, but shipped: the build loads it, found by its class like the worker's jar.
    runtimeOnly(project(":mutastic-maven-extension"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
