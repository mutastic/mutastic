// One test class for each place JUnit 4 can run code from, each calling a method of its own in
// Sites: what a mutant does there says what mutastic makes of that place. JUnit 4 runs on the JUnit
// Platform through its vintage engine.
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
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly(platform("org.junit:junit-bom:5.12.2"))
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
