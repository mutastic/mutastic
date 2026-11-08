// A small Compose for Desktop app, built with JetBrains' Compose Multiplatform plugin so it runs on a
// plain JVM: what the Compose compiler writes into each @Composable is told from the person's code
// in it. Some tests drive the UI, some test the state holders alone, and not all are thorough.
plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
