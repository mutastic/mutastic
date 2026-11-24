package io.mutastic.gradle.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Which launcher a test task's classpath needs: the build's engine's version, or none.
class PlatformLauncherTest {
  private static List<File> jars(String... names) {
    return java.util.Arrays.stream(names).map(name -> new File("cache/" + name)).toList();
  }

  @Test
  void aPlatformWithNoLauncherNeedsOneAtItsVersion() {
    assertThat(
            PlatformLauncher.engineVersion(
                jars("junit-jupiter-engine-5.10.2.jar", "junit-platform-engine-1.10.2.jar")))
        .contains("1.10.2");
  }

  @Test
  void aLauncherOrNoPlatformNeedsNone() {
    assertThat(
            PlatformLauncher.engineVersion(
                jars("junit-platform-engine-1.10.2.jar", "junit-platform-launcher-1.10.2.jar")))
        .isEmpty();
    assertThat(PlatformLauncher.engineVersion(jars("junit-4.13.2.jar"))).isEmpty();
  }
}
