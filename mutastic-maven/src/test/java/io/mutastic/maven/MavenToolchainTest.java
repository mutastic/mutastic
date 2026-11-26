package io.mutastic.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuildErrors.Toolchain;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// The toolchains plugin's words when `~/.m2/toolchains.xml` has no JDK the build asks for, as
/// maven-toolchains-plugin 3.2.0 on Maven 3.9.11 says them.
class MavenToolchainTest {
  private static final String FAILED =
      "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-toolchains-plugin:3.2.0"
          + ":toolchain (default) on project shop: Cannot find matching toolchain definitions for"
          + " the following toolchain types:";

  @Test
  void theJdkComesFromWhatMavenLookedFor() {
    var said =
        List.of(
            "[ERROR] No toolchain found for type jdk",
            "[ERROR] Cannot find matching toolchain definitions for the following toolchain types:",
            "[ERROR] jdk [ vendor='temurin' version='99' ]",
            "[INFO] BUILD FAILURE",
            FAILED,
            "[ERROR] jdk [ vendor='temurin' version='99' ]",
            "[ERROR] Please make sure you define the required toolchains in your"
                + " ~/.m2/toolchains.xml file.");

    assertThat(MavenToolchain.missing(said)).contains(new Toolchain("99", Optional.of("temurin")));
  }

  @Test
  void aRangeAndNoVendorAreAsTheBuildSaysThem() {
    var said = List.of(FAILED, "[ERROR] jdk [ version='[17,)' ]");

    assertThat(MavenToolchain.missing(said)).contains(new Toolchain("[17,)", Optional.empty()));
  }

  /// A toolchain of another type, or a JDK named by another failure, isn't one.
  @Test
  void anotherFailureIsntOne() {
    assertThat(MavenToolchain.missing(List.of(FAILED, "[ERROR] protobuf [ version='3' ]")))
        .isEmpty();
    assertThat(MavenToolchain.missing(List.of("[ERROR] jdk [ version='21' ]"))).isEmpty();
  }
}
