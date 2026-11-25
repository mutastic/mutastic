// A JDK 17 project whose parameterized test takes a class of its own test sources, in the same
// package as the code: mutastic runs a copy of both each run, and must select the test by the
// copy's parameter type, for mutastic's end-to-end tests. JUnit 6, as since 5.13 a selector that
// finds no method fails the run rather than selecting nothing.

plugins {
    java
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
