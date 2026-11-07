// Real kotlinc output, for what Kotlin compiles differently from Java: the null checks it writes
// into every method, and the empty values it has of its own.
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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
