// Loaded into the project's own Maven build with -Dmaven.ext.class.path, as the init script is
// into a Gradle build: after `mvn test-compile` it writes what Surefire would run. Built against
// the Maven it runs in (provided, never shipped).
plugins {
    `java-library`
}

description = "Loaded into a project's own Maven build to record what Surefire would run, for mutastic."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Built for 11, not 17 like the worker: Maven before 3.9.6 finds an extension's components with
// Sisu 0.3, which reads class files up to Java 14's and passes over newer ones without a word, so
// the extension never ran and every module read as having no Java code. ExtensionTest holds it.
tasks.withType<JavaCompile>().configureEach {
    options.release = 11
}

dependencies {
    compileOnly(libs.maven.core)
}
