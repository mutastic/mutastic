// A JDK 17 project whose tests use a library that finds the project's classes by its own class
// loader, as spring-petclinic's VetTests#serialization does: spring-core reads an object back with
// a plain ObjectInputStream, which resolves the class from the latest loader on the stack,
// spring-core's. Under Gradle the project and its libraries are on one class path, so it finds it.
// For mutastic's end-to-end tests.

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
    testImplementation("org.springframework:spring-core:6.2.1")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
