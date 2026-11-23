// A Spring Boot app whose tests share a configuration class: `TestApp`, in the tests but in the
// app's own package, scans that package and declares a bean. A scan of the package finds `TestApp`
// itself, so it has to be the class the tests name, or Spring sees two and refuses the bean twice.
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
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.15"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.15"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
