// A Quarkus app with @QuarkusTest tests: Quarkus loads the app in its own class
// loader, so a mutant has to reach that loader, and Mutiny's Uni/Multi are the operator plugin.
plugins {
    java
    id("io.quarkus") version "3.40.1"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.40.1"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-mutiny")
    testImplementation("io.quarkus:quarkus-junit5")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
