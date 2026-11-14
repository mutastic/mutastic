// Text that tries to give an agent orders, where mutastic quotes a project: a string literal and a
// comment on a mutated line, with an ANSI escape, a bidi override and a zero-width space in them; a
// test's name; and a test's data, read from a file, with an ANSI escape, that a failing test's
// message quotes. mutastic's output must fence it all off as data, and show the hidden characters
// as escapes. javac drops an escape from a name, so a test name's escapes are AgentTextTest's.

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
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
