// A JDK 17 project whose test task leaves tests out and sets the environment: mutastic must run
// the same tests with the same environment.

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
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("slow")
    }
    exclude("**/*IT.class")
    environment("FEE_RATE", "2")
}
