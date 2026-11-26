// Test classes in a package production code also uses (`demo`), as most projects have them, and
// tests that depend on a test class's own name: one in another package (`demo.args.Holder`) has a
// field of `demo.Point`, and one reads its nested class's name. mutastic once ran such a test class
// as a renamed copy each run (`BinderTest__m2`), which broke both before any mutant (A4).
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
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
