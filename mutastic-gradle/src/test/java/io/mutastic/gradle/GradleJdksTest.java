package io.mutastic.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuildErrors;
import io.mutastic.api.Wrappers;
import io.mutastic.gradle.GradleJdks.TooOld;
import io.mutastic.gradle.GradleJdks.Version;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A build whose Gradle can't run on its JDK is told so, and how out, rather than what Gradle says.
class GradleJdksTest {
  @Test
  void aGradleRunsOnTheJdksOfItsDayAndBefore() {
    assertThat(GradleJdks.tooOld(Version.of("8.10.2"), 25))
        .contains(new TooOld(Version.of("8.10.2"), 25, 23, Optional.of(Version.of("9.1.0"))));
    assertThat(GradleJdks.tooOld(Version.of("8.14.3"), 25).orElseThrow().newest()).isEqualTo(24);
    assertThat(GradleJdks.tooOld(Version.of("9.1.0"), 25)).isEmpty();
    assertThat(GradleJdks.tooOld(Version.of("8.10"), 23)).isEmpty();
    assertThat(GradleJdks.tooOld(Version.of("8.10.2"), 21)).isEmpty();
    assertThat(GradleJdks.tooOld(Version.of("7.6"), 8)).isEmpty();
  }

  /// A JDK newer than the matrix knows: too new for a Gradle too old for the newest it knows, and
  /// not said of any other, which may well run on it.
  @Test
  void aJdkNewerThanTheMatrixKnows() {
    assertThat(GradleJdks.tooOld(Version.of("9.2.1"), 40))
        .contains(new TooOld(Version.of("9.2.1"), 40, 25, Optional.empty()));
    assertThat(GradleJdks.tooOld(Version.of("10.0"), 40)).isEmpty();
  }

  @Test
  void theWrappersGradleIsItsDistributions(@TempDir Path build) throws Exception {
    var wrapper = build.resolve("gradlew");
    Files.createDirectories(build.resolve("gradle/wrapper"));
    var properties = build.resolve("gradle/wrapper/gradle-wrapper.properties");

    Files.writeString(
        properties,
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10.2-bin.zip\n");
    assertThat(GradleJdks.wrapped(wrapper)).contains(Version.of("8.10.2"));
    Files.writeString(
        properties, "distributionUrl=https\\://example.com/gradle-9.1.0-rc-1-all.zip\n");
    assertThat(GradleJdks.wrapped(wrapper)).contains(Version.of("9.1.0"));
    Files.writeString(properties, "distributionUrl=https\\://example.com/custom.zip\n");
    assertThat(GradleJdks.wrapped(wrapper)).isEmpty();
    assertThat(GradleJdks.wrapped(build.resolve("elsewhere/gradlew"))).isEmpty();
  }

  @Test
  void itSaysTheJdkTheGradleAndBothWaysOut() {
    var tooOld = GradleJdks.tooOld(Version.of("8.10.2"), 25).orElseThrow();
    var here = Path.of("").toAbsolutePath();

    var build =
        new BuildErrors.Build(
            "Gradle",
            "shop",
            Path.of("build/mutastic/gradle.log"),
            GradleJdks.upgrade(tooOld, "gradle", here));

    assertThat(GradleJdks.said(tooOld, build))
        .isEqualTo(
            """
            shop's build runs on JDK 25, and its Gradle, 8.10.2, runs on JDK 23 at most.
            Set JAVA_HOME to a JDK 23 or older, and mutastic still finds its own JDK 25. Or\
             upgrade the wrapper to Gradle 9.1.0 or later. Gradle's output is in\
             build/mutastic/gradle.log. To upgrade, with JAVA_HOME at the older JDK:
              gradle wrapper --gradle-version 9.1.0""");
  }

  /// Read when the build fails: the wrapper's Gradle, and the JDK its JAVA_HOME names.
  @Test
  void aFailedBuildIsAskedWhetherItsGradleRunsOnItsJdk(@TempDir Path work) throws Exception {
    var build = Files.createDirectories(work.resolve("shop"));
    Files.writeString(build.resolve("settings.gradle.kts"), "");
    Files.writeString(build.resolve(Wrappers.WINDOWS ? "gradlew.bat" : "gradlew"), "");
    build.resolve(Wrappers.WINDOWS ? "gradlew.bat" : "gradlew").toFile().setExecutable(true);
    Files.createDirectories(build.resolve("gradle/wrapper"));
    Files.writeString(
        build.resolve("gradle/wrapper/gradle-wrapper.properties"),
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10.2-bin.zip\n");
    var jdk25 = Files.createDirectories(work.resolve("jdk-25"));
    Files.writeString(jdk25.resolve("release"), "JAVA_VERSION=\"25.0.1\"\n");
    var jdk21 = Files.createDirectories(work.resolve("jdk-21"));
    Files.writeString(jdk21.resolve("release"), "JAVA_VERSION=\"21.0.9\"\n");

    assertThat(GradleSetup.tooOld(build, Map.of("JAVA_HOME", jdk25.toString())))
        .map(TooOld::newest)
        .contains(23);
    assertThat(GradleSetup.tooOld(build, Map.of("JAVA_HOME", jdk21.toString()))).isEmpty();
  }
}
