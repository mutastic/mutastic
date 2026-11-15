package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Two names for one directory compare equal, as `/var/…` and `/private/var/…` do on macOS.
class RealPathTest {
  @TempDir Path work;

  @Test
  void aLinkAndItsTargetAreOnePath() throws Exception {
    var target = Files.createDirectories(work.resolve("private/var/build"));
    var link = Files.createSymbolicLink(work.resolve("var"), work.resolve("private/var"));

    assertThat(RealPath.of(link.resolve("build"))).isEqualTo(RealPath.of(target));
    assertThat(RealPath.of(link.resolve("build/core/../core")))
        .isEqualTo(target.toRealPath().resolve("core"));
  }

  /// A path not made yet resolves as far as it's there, so it still compares with one that is.
  @Test
  void aPathNotMadeYetResolvesWhatsAboveIt() throws Exception {
    Files.createDirectories(work.resolve("private/var"));
    var link = Files.createSymbolicLink(work.resolve("var"), work.resolve("private/var"));

    assertThat(RealPath.of(link.resolve("build/libs/core.jar")))
        .isEqualTo(work.toRealPath().resolve("private/var/build/libs/core.jar"))
        .startsWithRaw(RealPath.of(work.resolve("private/var")));
  }
}
