// The engine: finds mutants with the ClassFile API (final in JDK 24), runs the coverage pass and
// each mutant's tests in a worker on the project's own JDK, and reports. Runs on JDK 25.
plugins {
    `java-library`
}

description = "mutastic's engine: finds, filters, applies and verifies mutants with the ClassFile API, and runs their tests."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api(project(":mutastic-api"))
    // The worker's own rule for which tests a name leaves out (TestNames), so the engine's choice
    // of test classes and the flaky watch match names exactly as the worker's filter does. The
    // worker depends on nothing, so nothing else of it comes along.
    implementation(project(":mutastic-worker"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    // The fixtures use mutastic's own annotations as a project would; the engine itself never does.
    testImplementation(project(":mutastic-annotations"))
    // The cards' truth check reads a mutant's card as a person would, from the report.
    testImplementation(project(":mutastic-report"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// The JUnit Platform's engines for TestNG's tests and JUnit 4's, for a project whose build runs
// them without the platform: inside the jar as resources, not on any classpath, since a platform
// that finds one there would try to run its tests. The engine copies one out for the workers that
// need it.
val testngEngine by configurations.creating {
    isTransitive = false
}
val vintageEngine by configurations.creating {
    isTransitive = false
}

dependencies {
    testngEngine(libs.testng.engine)
    vintageEngine(libs.junit.vintage.engine)
}

tasks.processResources {
    from(testngEngine) {
        into("io/mutastic/engine/tests")
        rename { "testng-engine.jar" }
    }
    from(vintageEngine) {
        into("io/mutastic/engine/tests")
        rename { "vintage-engine.jar" }
    }
}
