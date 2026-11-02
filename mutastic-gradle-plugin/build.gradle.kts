// The Gradle plugin, `id("io.mutastic")`: mutastic's commands as tasks in each Java project. Each
// writes the module's setup from inside the build, as the init script does from outside it, then
// runs the command on JDK 25 (a Gradle toolchain) in a process of its own. Built for 17, the oldest
// JDK Gradle runs on.
plugins {
    `java-library`
    `java-gradle-plugin`
}

description = "The Gradle plugin: tasks mutasticRun, mutasticVerify, mutasticExplain and mutasticIgnore."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    (options as ExtensionAware).extensions.configure<net.ltgt.gradle.errorprone.ErrorProneOptions> {
        // Gradle injects its services into an abstract getter of the task class it generates: its
        // documented way, which plain javax.inject, the check's assumption, can't do.
        disable("JavaxInjectOnAbstractMethod")
    }
}

gradlePlugin {
    plugins {
        create("mutastic") {
            id = "io.mutastic"
            implementationClass = "io.mutastic.gradle.plugin.MutasticPlugin"
            displayName = "mutastic"
            description = project.description
        }
    }
}

// The plugin resolves the `mutastic` command of its own version.
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}
