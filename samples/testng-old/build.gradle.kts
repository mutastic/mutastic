// TestNG 6.9.10, older than the 6.14.3 TestNG's engine for the JUnit Platform runs: the build's
// own `useTestNG()` runs its tests, and mutastic says why it can't.
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
    testImplementation("org.testng:testng:6.9.10")
}

tasks.test {
    useTestNG()
}
