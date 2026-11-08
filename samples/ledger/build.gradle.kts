// A family of JavaBeans whose setters tell listeners what changed, through PropertyChangeSupport.
// Most writes fire a property change and a few don't: some on purpose, some forgotten. Ghosts
// (insertion mutants, experimental) are tried on it.

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
