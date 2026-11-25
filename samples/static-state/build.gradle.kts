// A JDK 17 project whose tests keep state in statics, each in a different shape: a counter, a
// remembered result, a collection, an object of the tests' own, a plain Object and a nested class.
// One run of the build runs each test once, from clean statics, so none of them can fail. Each
// test alone reaches one method of Shop, so what it says of that method's mutants is what the
// build would. For mutastic's end-to-end tests.

plugins {
    java
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
