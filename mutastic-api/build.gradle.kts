// What everything else shares: the result model (a project, its mutants and their results) and,
// as they're added, the extension points. Depends on nothing but the JDK.
plugins {
    `java-library`
}

description = "mutastic's result model and extension points: build tools, filters, policies and test selection."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// The docs' reference of the API, from its own doc comments: a doclet, in a source set of its own
// that ships nowhere, writes docs/src/data/api.json, which docs.mutastic.io renders. Committed, so
// the docs build needs no Java; check fails while the copy and the comments disagree. The
// annotations a project puts in its code are part of it.
val doclet by sourceSets.creating
dependencies {
    "docletCompileOnly"("org.jspecify:jspecify:1.0.1")
}
val annotationSources = rootProject.file("mutastic-annotations/src/main/java")
val apiModel = rootProject.file("docs/src/data/api.json")
val writeApiModel by tasks.registering(JavaExec::class) {
    description = "Writes the docs' model of the API to build/docs."
    classpath = doclet.runtimeClasspath
    mainClass = "io.mutastic.api.docs.ApiModel"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val out = layout.buildDirectory.file("docs/api.json")
    val sources = file("src/main/java")
    inputs.dir(sources)
    inputs.dir(annotationSources)
    outputs.file(out)
    argumentProviders.add {
        listOf(
            out.get().asFile.absolutePath,
            sourceSets.main
                .get()
                .compileClasspath.asPath,
            sources.absolutePath,
            annotationSources.absolutePath,
        )
    }
}
tasks.register<Copy>("exportApiModel") {
    description = "Writes the docs' model of the API to docs/src/data."
    from(writeApiModel)
    into(apiModel.parentFile)
}
val checkApiModel by tasks.registering {
    description = "Fails when docs/src/data/api.json isn't what the API's comments say."
    val written = writeApiModel.map { it.outputs.files.singleFile }
    inputs.files(written, apiModel)
    doLast {
        if (!apiModel.exists() || apiModel.readText() != written.get().readText()) {
            throw GradleException("docs/src/data/api.json is out of date: ./gradlew :mutastic-api:exportApiModel")
        }
    }
}
tasks.check {
    dependsOn(checkApiModel)
}
