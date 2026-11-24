- **The Gradle plugin reads a test task that has no launcher.** Its `mutasticWriteSetup` asked
  the test task's launcher alone, so a build that clears it failed in that task. It now takes the
  task's `executable`, the JDK Gradle runs on unless the build sets another, as the `mutastic`
  command's setup does.
