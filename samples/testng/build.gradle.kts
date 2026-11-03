// TestNG tests and no JUnit at all: mutastic runs them on the JUnit Platform with TestNG's
// engine, which it brings. The `slow` group is left out, as the test task leaves it out.
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
    testImplementation("org.testng:testng:7.11.0")
}

tasks.test {
    useTestNG {
        excludeGroups("slow")
    }
}
