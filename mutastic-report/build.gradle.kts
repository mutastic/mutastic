// The formats: people's (full, brief, concise, grouped), agents', JSON Lines, and the report
// files other tools read. They know the result model, not how the engine found it.
plugins {
    `java-library`
}

description = "mutastic's output formats: for people, for agents, JSON Lines, and report files other tools read."

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
    testImplementation(libs.json.schema.validator)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The schemas docs.mutastic.io serves, which every event the tests write is checked against.
    val schemas = rootDir.resolve("docs/public/schemas")
    systemProperty("mutastic.schemas", schemas.absolutePath)
    inputs.dir(schemas).withPathSensitivity(PathSensitivity.RELATIVE)
    // SchemaCompatibilityTest holds the schema to the last 1.0+ release's, once there is one.
    systemProperty(
        "mutastic.lastStableRelease",
        providers.gradleProperty("lastStableRelease").getOrElse(""),
    )
}
