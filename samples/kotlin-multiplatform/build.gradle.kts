// A Kotlin Multiplatform module with one JVM target, as a desktop app has: no `java` plugin, its
// classes in the target's compilations, its tests in the target's own Test task (`desktopTest`),
// and files named for where they run (`Tally.common.kt`, `Tally.desktop.kt`). `kotlin("test")`
// picks JUnit 4 for the target, as it does by default: the tests have no JUnit Platform of their
// own.
plugins {
    kotlin("multiplatform") version "2.2.21"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
    jvm("desktop")

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
