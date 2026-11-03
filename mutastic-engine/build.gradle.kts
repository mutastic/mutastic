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

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    // The fixtures use mutastic's own annotations as a project would; the engine itself never does.
    testImplementation(project(":mutastic-annotations"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// TestNG's engine for the JUnit Platform, for a project whose tests are TestNG's: inside the jar
// as a resource, not on any classpath, since a platform that finds it there would try to run
// TestNG. The engine copies it out for the workers that need it.
val testngEngine by configurations.creating {
    isTransitive = false
}

dependencies {
    testngEngine(libs.testng.engine)
}

tasks.processResources {
    from(testngEngine) {
        into("io/mutastic/engine")
        rename { "testng-engine.jar" }
    }
}
