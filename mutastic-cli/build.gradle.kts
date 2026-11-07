// The `mutastic` command: reads a Gradle project's setup, runs the engine, prints the result.
plugins {
    application
    // Also a library: the Maven plugin runs it, from the plugin's own classpath.
    `java-library`
}

description = "The mutastic command, which the build plugins run too."

// Its jar goes in the distribution and its end-to-end tests.
evaluationDependsOn(":mutastic-plugins")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(project(":mutastic-engine"))
    implementation(project(":mutastic-report"))
    // Found at run time as BuildTools: the CLI names none of them.
    runtimeOnly(project(":mutastic-gradle"))
    runtimeOnly(project(":mutastic-maven"))
    // Not called, but shipped: the engine starts it as its own process, as a Java agent.
    runtimeOnly(project(":mutastic-worker"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    // Only so ArchUnit sees the annotations module; the CLI never uses it.
    testRuntimeOnly(project(":mutastic-annotations"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    applicationName = "mutastic"
    mainClass = "io.mutastic.cli.Main"
}

// The operators mutastic makes as plugins, for a project to list under `plugins:`: off the
// classpath, so that listing them is what turns them on.
distributions {
    main {
        contents {
            into("plugins") {
                from(project(":mutastic-plugins").tasks.named("jar"))
                from(rootDir.resolve("docs/src/data/plugins/bigdecimal.yaml"))
                from(rootDir.resolve("docs/src/data/plugins/reactive.yaml"))
            }
        }
    }
}

// The output the docs show (docs/src/data/output): mutastic's own, on the pricing sample.
val docsOutput = rootDir.resolve("docs/src/data/output")

// Every card mutastic gives on the samples (src/test/cards), so a change to their words is a diff.
val cards = layout.projectDirectory.dir("src/test/cards").asFile

// Mutants' ids in a fixed set of class files (src/test/ids), which every later version must keep.
val ids = layout.projectDirectory.dir("src/test/ids").asFile

tasks.test {
    useJUnitPlatform()
    // The end-to-end tests run mutastic against the sample projects, each its own Gradle build.
    systemProperty("mutastic.samples", rootDir.resolve("samples").absolutePath)
    // DocsCommandsTest parses every command the docs show.
    systemProperty("mutastic.docs", rootDir.resolve("docs/src/content/docs").absolutePath)
    // An input, so a change to the docs alone runs it again.
    inputs.dir(rootDir.resolve("docs/src/content/docs")).withPathSensitivity(PathSensitivity.RELATIVE)
    // DocsOutputTest checks the output the docs show is what mutastic prints.
    systemProperty("mutastic.docsOutput", docsOutput.absolutePath)
    inputs.dir(docsOutput).withPathSensitivity(PathSensitivity.RELATIVE)
    // CardsTest checks every card on the samples is the one kept.
    systemProperty("mutastic.cards", cards.absolutePath)
    inputs.dir(cards).withPathSensitivity(PathSensitivity.RELATIVE)
    // StableIdsTest holds the mutants of a fixed set of class files to the ids kept beside them.
    systemProperty("mutastic.ids", ids.absolutePath)
    inputs.dir(ids).withPathSensitivity(PathSensitivity.RELATIVE)
    // The plugins' end-to-end tests list the jar as a project would.
    val pluginsJar = project(":mutastic-plugins").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(pluginsJar).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("mutastic.pluginsJar", pluginsJar.get().asFile.absolutePath)
    // DocsPluginsTest reads the example plugins the docs show.
    inputs.dir(rootDir.resolve("docs/src/data/plugins")).withPathSensitivity(PathSensitivity.RELATIVE)
    // The Maven plugin's test resolves it, and everything it needs, from a repository in the build.
    systemProperty(
        "mutastic.samplesRepository",
        rootProject.layout.buildDirectory
            .dir("samples-repo")
            .get()
            .asFile.absolutePath,
    )
    systemProperty("mutastic.version", project.version.toString())
    // mutastic's own code that these tests run outside their JVM is measured too: the worker, in
    // a JVM of its own; the Maven extension and plugin, inside Maven; the Gradle plugin, inside
    // the samples' Gradle daemons. Each writes to its own file in jacoco/outside, which the
    // coverage report reads.
    val outside = layout.buildDirectory.dir("jacoco/outside")
    outputs.dir(outside)
    doFirst {
        val agent = the<JacocoTaskExtension>().asJvmArg.substringAfter("-javaagent:").substringBefore("=")
        val directory = outside.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()

        fun measured(name: String) = "-javaagent:$agent=destfile=$directory/$name.exec,append=true,includes=io.mutastic.*"
        systemProperty("mutastic.workerJvmArgs", measured("worker-{worker}"))
        systemProperty("mutastic.coverageAgent", measured("gradle"))
        environment("MAVEN_OPTS", measured("maven"))
    }
    dependsOn(rootProject.subprojects.map { "${it.path}:publishAllPublicationsToSamplesRepository" })
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

// The docs reference's model of the commands and options (docs/src/data/commands.json), written
// from the CLI's own declarations, as `--help` is. Committed, so the docs build needs no Java; the
// check fails while the committed copy and the code disagree.
val docsModel = rootProject.layout.projectDirectory.file("docs/src/data/commands.json")
val writeDocsModel by tasks.registering(JavaExec::class) {
    description = "Writes the docs' model of the commands and options to build/docs."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.mutastic.cli.DocsModel"
    val out = layout.buildDirectory.file("docs/commands.json")
    args(out.get().asFile.absolutePath)
    outputs.file(out)
}
tasks.register<Copy>("exportDocsModel") {
    description = "Writes the docs' model of the commands and options to docs/src/data."
    from(writeDocsModel)
    into(docsModel.asFile.parentFile)
}
val checkDocsModel by tasks.registering {
    description = "Fails when docs/src/data/commands.json isn't what the code declares."
    val written = writeDocsModel.map { it.outputs.files.singleFile }
    val committed = docsModel.asFile
    inputs.files(written, committed)
    doLast {
        if (!committed.exists() || committed.readText() != written.get().readText()) {
            throw GradleException(
                "docs/src/data/commands.json is out of date: ./gradlew :mutastic-cli:exportDocsModel",
            )
        }
    }
}
tasks.check {
    dependsOn(checkDocsModel)
}
// Writes the docs' copy of mutastic's output again, after a change to what it prints. Committed, so
// the docs build needs no Java; DocsOutputTest fails while the copy and the code disagree.
tasks.register<Test>("exportDocsOutput") {
    description = "Writes the output the docs show to docs/src/data/output."
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("io.mutastic.cli.DocsOutputTest") }
    systemProperty("mutastic.samples", rootDir.resolve("samples").absolutePath)
    systemProperty("mutastic.docsOutput", docsOutput.absolutePath)
    systemProperty("mutastic.docsOutput.write", "true")
    outputs.upToDateWhen { false }
}

// Writes the samples' cards again, after a change to what they say: read the diff before committing.
tasks.register<Test>("exportCards") {
    description = "Writes every card on the samples to src/test/cards."
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("io.mutastic.cli.CardsTest") }
    systemProperty("mutastic.samples", rootDir.resolve("samples").absolutePath)
    systemProperty("mutastic.cards", cards.absolutePath)
    systemProperty("mutastic.cards.write", "true")
    outputs.upToDateWhen { false }
}

// Writes the kept ids again, after a new operator or a change meant to move ids: read the diff
// before committing, since a moved id breaks the ignores and baselines that name it.
tasks.register<Test>("exportIds") {
    description = "Writes the kept ids (src/test/ids/ids.tsv) and how released rules files read."
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("io.mutastic.cli.StableIdsTest")
        includeTestsMatching("io.mutastic.cli.RulesFileFormatTest")
    }
    val pluginsJar = project(":mutastic-plugins").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(pluginsJar).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("mutastic.pluginsJar", pluginsJar.get().asFile.absolutePath)
    systemProperty("mutastic.docs", rootDir.resolve("docs/src/content/docs").absolutePath)
    systemProperty("mutastic.ids", ids.absolutePath)
    systemProperty("mutastic.ids.write", "true")
    outputs.upToDateWhen { false }
}
