// JUnit 5 with no junit-platform-launcher declared: Gradle 8 brings its own launcher, so many
// builds never declare one. mutastic adds the build's own launcher, at its engine's version.
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
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
