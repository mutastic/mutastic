// The operators mutastic makes as plugins, not in its own set: annotation removal, Kotlin's empty
// returns and proposed rewrites. One jar a project lists under `plugins:` in mutastic.yaml, like
// any other plugin: it uses only the API a plugin has. It ships in the distribution's `plugins`
// directory, and to Maven Central with the other modules, as `io.mutastic:mutastic-plugins`, for a
// build to fetch.
plugins {
    `java-library`
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
