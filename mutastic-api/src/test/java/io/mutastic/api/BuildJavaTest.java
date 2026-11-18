package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The build runs on the JDK it was given, not on mutastic's.
class BuildJavaTest {
  @Test
  void theBuildGetsBackTheJavaHomeItWasGiven() {
    var environment =
        new HashMap<>(
            Map.of("JAVA_HOME", "/jdk-25", BuildJava.KEPT, "/jdk-21", "PATH", "/usr/bin"));

    BuildJava.restore(environment);

    assertThat(environment).isEqualTo(Map.of("JAVA_HOME", "/jdk-21", "PATH", "/usr/bin"));
  }

  /// Blank, for none: the build finds its java on the PATH, as it would have.
  @Test
  void aBuildGivenNoJavaHomeGetsNone() {
    var empty = new HashMap<>(Map.of("JAVA_HOME", "/jdk-25", BuildJava.KEPT, ""));
    var space = new HashMap<>(Map.of("JAVA_HOME", "/jdk-25", BuildJava.KEPT, " "));

    BuildJava.restore(empty);
    BuildJava.restore(space);

    assertThat(empty).isEmpty();
    assertThat(space).isEmpty();
  }

  /// Run by a build plugin, or without the start script, nothing was kept, and nothing changes.
  @Test
  void withoutTheStartScriptNothingChanges() {
    var environment = new HashMap<>(Map.of("JAVA_HOME", "/jdk-25"));

    BuildJava.restore(environment);

    assertThat(environment).isEqualTo(Map.of("JAVA_HOME", "/jdk-25"));
  }

  @Test
  void aJdksFeatureVersionIsReadFromItsReleaseFile(@TempDir Path jdk) throws Exception {
    Files.writeString(jdk.resolve("release"), "IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"25.0.1\"\n");

    assertThat(BuildJava.feature(Map.of("JAVA_HOME", jdk.toString()))).hasValue(25);
    assertThat(BuildJava.fromRelease("JAVA_VERSION=\"1.8.0_392\"")).hasValue(8);
    assertThat(BuildJava.fromRelease("JAVA_VERSION=\"21\"")).hasValue(21);
    assertThat(BuildJava.fromRelease("nothing")).isEqualTo(OptionalInt.empty());
  }

  @Test
  void orFromWhatItsJavaSays() {
    assertThat(BuildJava.fromSettings("    java.specification.version = 17\n")).hasValue(17);
    assertThat(BuildJava.fromSettings("    java.specification.version = 1.8\n")).hasValue(8);
    assertThat(BuildJava.fromSettings("Unable to locate a Java Runtime.")).isEmpty();
  }

  /// This JVM's own java, found on the PATH, as a build without JAVA_HOME finds it.
  @Test
  void withoutJavaHomeItsTheJavaOnThePath() {
    var bin = Path.of(System.getProperty("java.home"), "bin");

    assertThat(BuildJava.feature(Map.of("PATH", bin.toString())))
        .hasValue(Runtime.version().feature());
    assertThat(BuildJava.feature(Map.of("PATH", ""))).isEmpty();
  }
}
