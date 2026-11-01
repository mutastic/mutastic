// What everything else shares: the result model (a project, its mutants and their results) and,
// as they're added, the extension points. Depends on nothing but the JDK.
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
