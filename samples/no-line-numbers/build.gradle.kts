// Compiled without line numbers, as `-g:none` (or Maven's <debug>false</debug>) does: mutastic
// can't tell which tests reach a mutant, so it refuses, naming the classes, rather than call every
// mutant reached by no test.
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
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.compileJava {
    options.compilerArgs.add("-g:none")
}

tasks.test {
    useJUnitPlatform()
}
