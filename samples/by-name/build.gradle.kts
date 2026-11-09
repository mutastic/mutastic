// Two JDK 17 modules: `:shapes` loads `:fast`'s class by name (`Class.forName`), and `:fast`'s
// tests pass only if that lookup finds it, as it does on the build's one classpath.

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(17)
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.12.2"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

project(":fast") {
    dependencies {
        "implementation"(project(":shapes"))
    }
}
