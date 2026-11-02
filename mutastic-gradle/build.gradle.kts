// Gradle builds: an init script added with `-I` reads what each module's `test` task would run.
// Found by the CLI as a BuildTool.
plugins {
    `java-library`
}

description = "Reads a Gradle build's test setup for mutastic."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api(project(":mutastic-api"))
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
