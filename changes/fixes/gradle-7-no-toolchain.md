- **A Gradle 7 build without a toolchain is read, not refused as one that doesn't build.** Gradle 7
  gives the test task a launcher only when the build has a toolchain, and mutastic's setup asked
  the launcher alone, so it failed with "Cannot query the value of task ':test' property
  'javaLauncher'". It now takes the task's `executable`, the JDK Gradle runs on unless the build
  sets another. The minimum, Gradle 7.3, is on the
  [Install](https://docs.mutastic.io/getting-started/install/#which-gradle-and-maven-run) page.
