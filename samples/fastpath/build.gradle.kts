// The Equivalent mutants page's example of a check that only picks between a fast way and a safe
// way: below ten digits a number is read in an int, at any length in a long that can't overflow
// unnoticed. Both give the same answer below ten digits, so the check's own mutants are missed.
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
    useJUnitPlatform()
}
