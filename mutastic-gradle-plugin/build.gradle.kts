// The Gradle plugin, `id("io.mutastic")`: mutastic's commands as tasks in each Java project. Each
// writes the module's setup from inside the build, as the init script does from outside it, then
// runs the command on JDK 25 (a Gradle toolchain) in a process of its own. Built for 17, the oldest
// JDK Gradle runs on.
import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-library`
    `java-gradle-plugin`
    id("com.gradle.plugin-publish")
}

// What the Gradle Plugin Portal shows as its listing, and Maven Central as the POM's description.
description =
    "Mutation testing for Java: finds the changes your tests don't notice, and suggests the test that would catch each one."

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

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Published to Maven Central with the other modules, and to the Gradle Plugin Portal.
gradlePlugin {
    website = "https://github.com/mutastic/mutastic"
    vcsUrl = "https://github.com/mutastic/mutastic"
    plugins {
        create("mutastic") {
            id = "io.mutastic"
            implementationClass = "io.mutastic.gradle.plugin.MutasticPlugin"
            displayName = "mutastic"
            description = project.description
            tags = listOf("mutation-testing", "testing", "test-quality", "java")
            // Neither is: the setup task reads the test task as it runs, and isolated projects
            // haven't been tried. Declared false until each is, as the portal asks.
            compatibility {
                features {
                    configurationCache = false
                    isolatedProjects = false
                }
            }
        }
    }
}

// The plugin resolves the `mutastic` command of its own version.
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}
