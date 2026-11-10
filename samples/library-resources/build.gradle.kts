// Two JDK 17 modules. `:labels` stands in for a library from a repository: `:app` takes its jar as a
// file, so it's on the test classpath as a library is, not as one of the build's modules. It looks
// `:app`'s messages up with its own class loader, as `ResourceBundle.getBundle(name, locale)` does.
// `:app` also has a class whose static initialiser fails without a setting, as a test checks.
// `:labels` has no tests, and no JUnit.

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
}

project(":app") {
    dependencies {
        "implementation"(files("../labels/build/libs/labels.jar").builtBy(":labels:jar"))
        "testImplementation"(platform("org.junit:junit-bom:5.12.2"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
