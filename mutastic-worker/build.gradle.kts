// The test worker: runs the user's tests on the user's JDK. Built for 17, the oldest JDK
// mutastic supports, and without the ClassFile API: it never reads bytecode, the engine sends it
// finished classes. It is also a Java agent, which is how mutants are swapped into loaded classes.
plugins {
    `java-library`
}

// Compiled on 25 with --release 17: checked against 17's API, and the checks that run inside
// the compiler (Error Prone needs 21 or later) run here too. Its tests run on 17 itself.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    // The project's own JUnit Platform runs its tests; the worker only drives it.
    compileOnly(libs.junit.platform.launcher.floor)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    // Compiled against too: some tests drive a launcher over fixture classes.
    testImplementation(libs.junit.platform.launcher)
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "io.mutastic.worker.Agent",
            "Can-Redefine-Classes" to "true",
        )
    }
}

tasks.test {
    // Fixtures are test classes the worker's own tests run through a launcher, not tests.
    useJUnitPlatform { excludeTags("fixture") }
    // On 17, the oldest JDK a project under test can use: what the worker promises.
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
}
