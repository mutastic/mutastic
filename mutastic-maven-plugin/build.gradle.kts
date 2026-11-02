// The Maven plugin: `mvn mutastic:run`, and mutastic's other commands as goals. Each writes the
// module's setup from inside the build, as the extension does from outside it, then runs the
// command on JDK 25 in a process of its own, since Maven may run on 17. Built for 17 against the
// oldest Maven mutastic supports, like the extension it uses.
plugins {
    `java-library`
    id("org.gradlex.maven-plugin-development")
}

description = "The Maven plugin: mvn mutastic:run, verify, explain and ignore."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    implementation(project(":mutastic-maven-extension"))
    // Not called here: run in a process of its own, on JDK 25, from the plugin's own classpath.
    runtimeOnly(project(":mutastic-cli"))

    compileOnly(libs.maven.core)
    compileOnly(libs.maven.plugin.api)
    compileOnly(libs.maven.plugin.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// The plugin's own classes run in Maven's JVM, so on 17; mutastic's command, among its runtime
// jars, runs in a JVM of its own on 25. Resolved for 25, or Gradle refuses it as too new for 17.
listOf(configurations.runtimeClasspath, configurations.testRuntimeClasspath).forEach {
    it {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }
}

mavenPlugin {
    goalPrefix = "mutastic"
}

configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
    pom {
        packaging = "maven-plugin"
    }
}
