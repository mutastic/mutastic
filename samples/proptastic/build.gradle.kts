// Functions checked only by proptastic's properties. proptastic isn't published yet, so the tests
// run against `src/surface`: its published surface and nothing more, its class names, system
// properties and failure report as its contract writes them, built into a jar named for no one.
// Set the Gradle property `proptastic` to the library's coordinates, such as
// `-Pproptastic=io.proptastic:proptastic:1.0.0`, and the same tests run against the library.
plugins {
    java
}

repositories {
    mavenCentral()
    mavenLocal()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

val surface by sourceSets.creating

val surfaceJar by tasks.registering(Jar::class) {
    from(surface.output)
    archiveFileName = "testkit.jar"
    destinationDirectory = layout.buildDirectory.dir("testkit")
}

val proptastic = providers.gradleProperty("proptastic").orNull

dependencies {
    "surfaceImplementation"("org.opentest4j:opentest4j:1.3.0")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(if (proptastic != null) proptastic else files(surfaceJar))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
