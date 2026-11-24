// Functions checked only by property tests: QuickTheories' and junit-quickcheck's in Java, on
// JUnit 5 and, through the vintage engine, JUnit 4, and Kotest's in Kotlin. A property draws its
// inputs at random, so a mutant only a few inputs catch, as `isAdult`'s boundary is caught only
// at 18, is caught or missed by the draw: how mutastic seeds it, and names the input that caught.
plugins {
    kotlin("jvm") version "2.2.21"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.quicktheories:quicktheories:0.26")
    testImplementation("com.pholser:junit-quickcheck-core:1.0")
    testImplementation("com.pholser:junit-quickcheck-generators:1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.kotest:kotest-property:6.2.7")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
