// The docs' operator examples: one class for each operator, and a test that misses it. The card
// each family page shows is mutastic's own, on this project (DocsOperatorsTest).
plugins {
    java
}

repositories {
    mavenCentral()
}

// 25, not the 17 the other samples use: the gatherer examples (streams/Batches, Statement) call
// java.util.stream.Gatherers, final in Java 24.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
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
