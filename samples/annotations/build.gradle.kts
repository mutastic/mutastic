// What annotation removal needs: annotations read at run time by real frameworks, which keep what
// they read. A validator held for the life of the JVM, and a Spring context with method security.
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
    implementation("org.hibernate.validator:hibernate-validator:8.0.1.Final")
    implementation("org.glassfish.expressly:expressly:5.0.0")
    implementation("org.springframework:spring-context:6.1.13")
    implementation("org.springframework.security:spring-security-config:6.3.3")
    implementation("org.springframework.security:spring-security-core:6.3.3")

    testImplementation("org.springframework:spring-test:6.1.13")
    testImplementation("org.springframework.security:spring-security-test:6.3.3")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
