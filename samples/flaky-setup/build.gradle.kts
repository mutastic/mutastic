// A class whose setup fails now and then, in each framework mutastic runs: JUnit 5's `@BeforeAll`,
// JUnit 4's and TestNG's `@BeforeClass`, and a JUnit 5 `@AfterAll` that fails after its test has
// passed. Each fails the first time it runs in a JVM and every third time after, so a catch by one
// of its tests could be the setup failing. A healthy class beside them checks the code properly.
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
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testImplementation("org.testng:testng:7.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
