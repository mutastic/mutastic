// Lombok writes accessors, equals and hashCode, toString, builders, constructors, null checks and
// a logger that aren't in the source: what mutastic makes of each, beside a few methods of the
// project's own. No lombok.config, as most projects have none.
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
    compileOnly("org.projectlombok:lombok:1.18.48")
    annotationProcessor("org.projectlombok:lombok:1.18.48")
    implementation("org.slf4j:slf4j-api:2.0.17")
    testCompileOnly("org.projectlombok:lombok:1.18.48")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.48")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

tasks.test {
    useJUnitPlatform()
}
