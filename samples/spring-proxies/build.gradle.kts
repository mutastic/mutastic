// Spring 5 keeps the CGLIB subclass it makes of a `@Configuration` class by the thread's context
// class loader, and defines it beside the class it extends. Each of mutastic's runs had a context
// loader of its own, so the second run in a worker defined the subclass again in the loader that
// outlasts the runs: the JVM refused it (`LinkageError: duplicate class definition`), the test
// failed, and that looked like a catch.
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
    implementation("org.springframework:spring-context:5.3.15")
    implementation("org.springframework:spring-tx:5.3.15")

    testImplementation("org.springframework:spring-test:5.3.15")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
