package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/// The start script runs mutastic on a JDK of its own, and the build on the one it was given
/// (src/scripts/engine-jdk.sh): MUTASTIC_JAVA_HOME when it's set, else the newest JDK 25 or later
/// of JAVA_HOME and those installed, since the project's class files may be newer than 25. The JDKs
/// here are of versions no machine has, so a real one never wins.
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Unix start script; mutastic.bat names one")
class EngineJdkTest {
  private static final Path SCRIPT = Path.of("src/scripts/engine-jdk.sh");

  @TempDir Path home;

  @Test
  void theNewestInstalledJdkRunsMutasticAndTheBuildKeepsItsOwn() throws Exception {
    jdk(".asdf/installs/java/temurin-90", 90, true);
    var newest = jdk(".gradle/jdks/adoptium-91/jdk-91/Contents/Home", 91, true);
    var project = jdk("project-21", 21, true);

    var found = engine(Map.of("JAVA_HOME", project.toString()));

    assertThat(found.out()).isEqualTo("engine=%s build=%s\n".formatted(newest, project));
  }

  /// A project on a JDK newer than any installed: mutastic runs on it too, to read its classes.
  @Test
  void aJavaHomeNewerThanAnyInstalledRunsMutasticToo() throws Exception {
    jdk(".sdkman/candidates/java/91", 91, true);
    var project = jdk("project-92", 92, true);

    var found = engine(Map.of("JAVA_HOME", project.toString()));

    assertThat(found.out()).isEqualTo("engine=%s build=%s\n".formatted(project, project));
  }

  /// A plugin's operators may compile code, which a runtime alone can't.
  @Test
  void aJdkBeforeARuntimeOfTheSameVersion() throws Exception {
    jdk(".asdf/installs/java/temurin-jre-91", 91, false);
    var full = jdk(".local/share/mise/installs/java/91", 91, true);

    assertThat(engine(Map.of()).out()).isEqualTo("engine=%s build=\n".formatted(full));
  }

  @Test
  void mutasticJavaHomeIsTakenAsItIs() throws Exception {
    jdk(".asdf/installs/java/temurin-91", 91, true);
    var named = jdk("named-90", 90, true);
    var tooOld = jdk("named-21", 21, true);

    assertThat(engine(Map.of("MUTASTIC_JAVA_HOME", named.toString())).out())
        .isEqualTo("engine=%s build=\n".formatted(named));
    var refused = engine(Map.of("MUTASTIC_JAVA_HOME", tooOld.toString()));
    assertThat(refused.exit()).isEqualTo(2);
    assertThat(refused.err())
        .isEqualTo(
            "mutastic: MUTASTIC_JAVA_HOME is %s, which isn't a JDK 25 or later: point it at one, or"
                    .formatted(tooOld)
                + " unset it for mutastic to find one\n");
  }

  @Test
  void withNoneItSaysWhereItLookedAndHowToNameOne() throws Exception {
    var project = jdk("project-21", 21, true);
    // Only the system's own directories, so a JDK the machine has elsewhere isn't found.
    var bare = engine(Map.of("JAVA_HOME", project.toString()));

    if (bare.exit() == 0) {
      // The machine has a JDK 25 where JDKs are kept for every user: found, and the build still
      // keeps its own.
      assertThat(bare.out()).startsWith("engine=/").endsWith(" build=" + project + "\n");
      return;
    }
    assertThat(bare.exit()).isEqualTo(2);
    assertThat(bare.err())
        .startsWith("mutastic: mutastic runs on JDK 25 or later, and found none. It looked at")
        .endsWith("  export MUTASTIC_JAVA_HOME=<a JDK 25 or later>\n");
  }

  /// A JDK of `feature` at `path` in the home: its release file, a `java`, and a `javac` if `full`.
  private Path jdk(String path, int feature, boolean full) throws IOException {
    var jdk = Files.createDirectories(home.resolve(path));
    Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"%d.0.1\"\n".formatted(feature));
    var bin = Files.createDirectories(jdk.resolve("bin"));
    executable(bin.resolve("java"));
    if (full) {
      executable(bin.resolve("javac"));
    }
    return jdk;
  }

  private static void executable(Path file) throws IOException {
    Files.writeString(file, "#!/bin/sh\nexit 1\n");
    file.toFile().setExecutable(true);
  }

  private record Ran(int exit, String out, String err) {}

  /// The script run in `home`, with `environment` and nothing else of this machine's but the
  /// system's commands: what it chose for mutastic, and what it kept for the build.
  private Ran engine(Map<String, String> environment) throws Exception {
    var command =
        ". \"$1\" && printf 'engine=%s build=%s\\n' \"$JAVA_HOME\" \"$MUTASTIC_BUILD_JAVA_HOME\"";
    var process =
        new ProcessBuilder("/bin/sh", "-c", command, "sh", SCRIPT.toAbsolutePath().toString());
    process.environment().clear();
    process.environment().putAll(Map.of("HOME", home.toString(), "PATH", "/usr/bin:/bin"));
    process.environment().putAll(environment);
    var err = home.resolve("err.txt");
    var started = process.redirectError(err.toFile()).start();
    var out = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new Ran(started.waitFor(), out, Files.readString(err));
  }
}
