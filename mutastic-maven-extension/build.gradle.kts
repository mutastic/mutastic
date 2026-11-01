// Loaded into the project's own Maven build with -Dmaven.ext.class.path, as the init script is
// into a Gradle build: after `mvn test-compile` it writes what Surefire would run. Built for 17,
// since Maven may run on 17, against the Maven it runs in (provided, never shipped).
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    compileOnly(libs.maven.core)
}
