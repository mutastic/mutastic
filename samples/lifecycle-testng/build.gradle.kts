// One test class for each place TestNG can run code from, each calling a method of its own in
// Sites: what a mutant does there says what mutastic makes of that place.
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
    testImplementation("org.testng:testng:7.11.0")
}

tasks.test {
    useTestNG()
}
