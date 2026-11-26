package io.mutastic.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuildErrors.Toolchain;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// Gradle's words when it finds no JDK for a toolchain, as Gradle 9.2.1 and 8.6 say them: the JDK
/// comes from what Gradle looked for, never from the build file.
class GradleToolchainTest {
  /// Auto-provisioning off, no download repository, and a download that failed, as Gradle 9 says
  /// each.
  @Test
  void theJdkComesFromWhatGradleLookedFor() {
    var off =
        """
              > Cannot find a Java installation on your machine (Mac OS X 26.5 aarch64) matching:\
         {languageVersion=21, vendor=any vendor, implementation=vendor-specific,\
         nativeImageCapable=false}. Toolchain auto-provisioning is not enabled.""";
    var noRepository =
        """
              > Cannot find a Java installation on your machine (Linux 6.8 amd64) matching:\
         {languageVersion=99, vendor=Eclipse Temurin, implementation=vendor-specific,\
         nativeImageCapable=false}. Toolchain download repositories have not been configured.""";
    var failed =
        """
              > Cannot find a Java installation on your machine (Mac OS X 26.5 aarch64) matching:\
         {languageVersion=22, vendor=Eclipse Temurin, implementation=vendor-specific,\
         nativeImageCapable=false}. Some toolchain resolvers had provisioning failures: foojay\
         (Unable to download toolchain matching the requirements ({languageVersion=22,\
         vendor=Eclipse Temurin, implementation=vendor-specific, nativeImageCapable=false}) from\
         'https://api.foojay.io/disco/v3.0/ids/248d/redirect', due to: Connection refused).""";

    assertThat(GradleToolchain.missing(off)).contains(new Toolchain("21", Optional.empty()));
    assertThat(GradleToolchain.missing(noRepository))
        .contains(new Toolchain("99", Optional.of("Eclipse Temurin")));
    assertThat(GradleToolchain.missing(failed))
        .contains(new Toolchain("22", Optional.of("Eclipse Temurin")));
  }

  @Test
  void gradle8sWordsAreReadToo() {
    var said =
        """
              > Cannot find a Java installation on your machine matching this tasks requirements:\
         {languageVersion=21, vendor=ADOPTIUM, implementation=vendor-specific} for MAC_OS on\
         aarch64.
                 > No locally installed toolchains match and toolchain download repositories have\
         not been configured.""";
    var anyVendor =
        "> Cannot find a Java installation on your machine matching this tasks requirements:"
            + " {languageVersion=17, vendor=any, implementation=vendor-specific} for LINUX on"
            + " x86_64.";

    assertThat(GradleToolchain.missing(said))
        .contains(new Toolchain("21", Optional.of("ADOPTIUM")));
    assertThat(GradleToolchain.missing(anyVendor)).contains(new Toolchain("17", Optional.empty()));
  }

  @Test
  void anotherFailureIsntOne() {
    assertThat(GradleToolchain.missing("> Could not resolve all files for configuration"))
        .isEmpty();
    assertThat(GradleToolchain.argument(new Toolchain("21", Optional.empty())))
        .isEqualTo("-Dorg.gradle.java.installations.paths=<path/to/jdk-21>");
  }
}
