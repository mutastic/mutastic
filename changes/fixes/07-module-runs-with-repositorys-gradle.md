- **A module runs with its repository's Gradle wrapper.** A module with a `settings.gradle` of
  its own, the wrapper only at the top of the repository, ran with the `gradle` on the `PATH`
  instead, and an older Gradle failed while it read the build file. mutastic now looks for
  `gradlew` and `mvnw` up to the top of the repository, and runs one that has lost its executable
  bit with `sh`. With no wrapper, it says which Gradle or Maven it runs from the `PATH`, with its
  path and version, and a failed build says that may be why. A Gradle on the `PATH` too old for
  the build's JDK is named as `build-jdk` too. The commands mutastic prints start with the same
  Gradle or Maven. See [Which Gradle and Maven
  run](https://docs.mutastic.io/getting-started/install/#which-gradle-and-maven-run).
