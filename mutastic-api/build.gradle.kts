// What everything else shares: the result model (a project, its mutants and their results) and,
// as they're added, the extension points. Depends on nothing but the JDK.
plugins {
    `java-library`
}

description = "mutastic's result model and extension points: build tools, filters, policies and test selection."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
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
