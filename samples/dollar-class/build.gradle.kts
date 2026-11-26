// Classes with `$` in their names, and no SourceFile attribute (`-g:lines` keeps the line numbers
// and leaves the file's name out, as bytecode a tool writes often does). `$Cache` is the code's own;
// `$Cache$Definition` is marked @Generated, as Micronaut 4 marks the definitions it writes.
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
    options.compilerArgs.add("-g:lines")
}

tasks.test {
    useJUnitPlatform()
}
