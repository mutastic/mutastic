// The test worker: runs the user's tests on the user's JDK. Built for 17, the oldest JDK
// mutastic supports, and without the ClassFile API: it never reads bytecode, the engine sends it
// finished classes. It is also a Java agent, which is how mutants are swapped into loaded classes.
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    // The project's own JUnit Platform runs its tests; the worker only drives it.
    compileOnly(libs.junit.platform.launcher.floor)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
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
    useJUnitPlatform()
}
