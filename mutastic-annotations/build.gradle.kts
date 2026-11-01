// The annotations a project may put in its own code: nothing else of mutastic's, and nothing at
// run time. Optional: mutastic matches them by simple name, so a team's own `@DoNotMutate` works
// the same. Built for 17, the oldest JDK a project mutastic tests can use.
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
