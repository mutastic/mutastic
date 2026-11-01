package io.mutastic.gradle;

import io.mutastic.api.BuildTool.SetupFailed;
import io.mutastic.api.Project;
import io.mutastic.api.ProjectSetup;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// A Gradle module's setup, read by running its build with mutastic's init script: compiles the
/// code and tests, and writes what the `test` task would use.
final class GradleSetup {
  private GradleSetup() {}

  /// `module` is a Gradle project path, `:` for the root project; `typed` is the directory as the
  /// person would type it, for the commands a failure suggests.
  static Project read(Path directory, String module, Path workDirectory, String typed) {
    var build = new Build(directory, workDirectory.resolve("mutastic.init.gradle.kts"));
    try {
      return setUp(build, module, workDirectory, typed);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while Gradle ran", e);
    }
  }

  /// A build to run, and mutastic's init script to run it with.
  private record Build(Path directory, Path init) {}

  /// Runs the module's `mutasticSetup` task and reads the project from what it wrote.
  private static Project setUp(Build build, String module, Path workDirectory, String typed)
      throws IOException, InterruptedException {
    var out = workDirectory.resolve("setup.properties");
    Files.createDirectories(workDirectory);
    writeInitScript(build.init());
    Files.deleteIfExists(out);
    var log = workDirectory.resolve("gradle.log");
    int exit = gradle(build, out, log, (module.equals(":") ? "" : module) + ":mutasticSetup");
    if (exit != 0 || !Files.exists(out)) {
      var output = Files.readString(log).strip();
      if (output.contains("'mutasticSetup' not found")) {
        throw new SetupFailed(noJava(build, module, workDirectory, typed));
      }
      throw new SetupFailed(
          "Gradle couldn't build "
              + build.directory()
              + " for mutastic (exit "
              + exit
              + "):\n"
              + output);
    }
    return ProjectSetup.read(name(build.directory(), module), out);
  }

  private static void writeInitScript(Path init) throws IOException {
    try (InputStream script = GradleSetup.class.getResourceAsStream("mutastic.init.gradle.kts")) {
      Files.write(init, script.readAllBytes());
    }
  }

  static final List<String> BUILD_FILES =
      List.of("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle");

  /// The module asked for has no Java code: say so, and name the modules that have some.
  private static String noJava(Build build, String module, Path workDirectory, String typed)
      throws IOException, InterruptedException {
    var modules = javaModules(build, workDirectory);
    var where =
        module.equals(":")
            ? "the root project of " + build.directory()
            : "module %s of %s".formatted(module, build.directory());
    if (modules.isEmpty()) {
      return "%s has no Java code for mutastic to test, and no module of it has.%n".formatted(where)
          + "Run mutastic on the project with your Java code and tests:\n"
          + "  mutastic run <project directory> [:module]\n"
          + SetupFailed.ID_HINT;
    }
    return javaElsewhere(where, modules, typed);
  }

  /// The build's modules with Java code, as its `mutasticModules` task lists them.
  private static List<String> javaModules(Build build, Path workDirectory)
      throws IOException, InterruptedException {
    var listed = workDirectory.resolve("java-modules.txt");
    Files.deleteIfExists(listed);
    gradle(build, listed, workDirectory.resolve("gradle-modules.log"), ":mutasticModules");
    return Files.exists(listed)
        ? Files.readAllLines(listed).stream().filter(line -> !line.isBlank()).toList()
        : List.<String>of();
  }

  /// `where` has no Java code but `modules` have: the commands to run on them instead.
  private static String javaElsewhere(String where, List<String> modules, String typed) {
    var on = typed.isEmpty() ? "" : " " + typed;
    var suggestions =
        modules.stream()
            .limit(5)
            .map(found -> "  mutastic run%s %s".formatted(on, found))
            .collect(java.util.stream.Collectors.joining("\n"));
    return """
        %s has no Java code for mutastic to test. Its modules with Java code are %s.
        Name one, and the same for an id from a run on it:
        %s
          mutastic run --only=<id>%s %s
        %s"""
        .formatted(
            where,
            String.join(", ", modules),
            suggestions,
            on,
            modules.getFirst(),
            SetupFailed.ID_HINT);
  }

  /// Runs the build's own Gradle with mutastic's init script; its output goes to `log`.
  private static int gradle(Build build, Path out, Path log, String task)
      throws IOException, InterruptedException {
    var command = gradleCommand(build, out, task);
    try {
      return new ProcessBuilder(command)
          .directory(build.directory().toFile())
          .redirectOutput(log.toFile())
          .redirectErrorStream(true)
          .start()
          .waitFor();
    } catch (IOException e) {
      if (command.getFirst().equals("gradle")) {
        throw new SetupFailed(
            "%s has no Gradle wrapper (gradlew), and there's no `gradle` to run instead"
                .formatted(build.directory()));
      }
      throw e;
    }
  }

  private static List<String> gradleCommand(Build build, Path out, String task) {
    return List.of(
        gradle(build.directory()),
        "-q",
        "--no-configuration-cache",
        "-I",
        build.init().toString(),
        "-PmutasticOut=" + out,
        task);
  }

  /// `pricing`, or `app/data` for `:data`.
  private static String name(Path directory, String module) {
    return directory.toAbsolutePath().normalize().getFileName()
        + (module.equals(":") ? "" : module.replace(':', '/'));
  }

  /// The project's wrapper, as its own build runs; `gradle` on the PATH without one.
  private static String gradle(Path directory) {
    var wrapper = directory.resolve(File.separatorChar == '\\' ? "gradlew.bat" : "gradlew");
    return Files.isExecutable(wrapper) ? wrapper.toAbsolutePath().toString() : "gradle";
  }
}
