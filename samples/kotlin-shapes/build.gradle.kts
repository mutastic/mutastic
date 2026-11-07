// Real kotlinc output for the shapes that make what's in the class file differ from what's in the
// source: code copied in from inline functions, a coroutine's state machine, what a `lateinit`
// writes, and the tests a card can ask for in Kotlin.
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
