package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import org.junit.jupiter.api.Test;

class BuildClassPathTest {
  private static String path(String... entries) {
    return String.join(File.pathSeparator, entries);
  }

  @Test
  void theTestsComeFirstThenTheCodeThenTheDependencies() {
    var args =
        new String[] {
          "--production=" + path("build/classes/java/main", "build/resources/main"),
          "--tests=" + path("build/classes/java/test", ""),
          "--resource-copy=copy"
        };

    assertThat(BuildClassPath.of(args, path("worker.jar", "lib/a.jar", "build/classes/java/main")))
        .isEqualTo(
            path(
                "build/classes/java/test",
                "build/classes/java/main",
                "build/resources/main",
                "worker.jar",
                "lib/a.jar"));
  }
}
