// The Equivalent mutants page's example of a value the code says is never negative: one method
// with the Checker Framework's @NonNegative, whose below-zero mutant mutastic skips, and the same
// method with Jakarta Validation's @PositiveOrZero, whose mutant runs.
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
    compileOnly("org.checkerframework:checker-qual:3.43.0")
    compileOnly("jakarta.validation:jakarta.validation-api:3.0.2")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
