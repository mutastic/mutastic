// The operators mutastic makes as plugins, not in its own set: annotation removal, Kotlin's empty
// returns and proposed rewrites. One jar a project lists under `plugins:` in mutastic.yaml, like
// any other plugin: it uses only the API a plugin has. It ships in the distribution's `plugins`
// directory. Not `java-library`, so it isn't published to Maven Central with each release, which
// counts every file against a monthly limit.
plugins {
    java
}

description = "The operators mutastic makes as plugins."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    compileOnly(project(":mutastic-api"))

    testImplementation(project(":mutastic-api"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName = "mutastic-plugins.jar"
}

// The CLI's end-to-end tests publish every module for Maven to resolve first: nothing to publish
// here.
tasks.register("publishAllPublicationsToSamplesRepository")
