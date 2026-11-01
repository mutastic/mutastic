package io.mutastic.cli;

import io.mutastic.engine.Project;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/// A Gradle module's setup, read by running its build with mutastic's init script: compiles the
/// code and tests, and writes what the `test` task would use.
final class GradleSetup {
  private GradleSetup() {}

  /// The build couldn't be read: it failed, or isn't a Java build.
  static final class SetupFailed extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    SetupFailed(String message) {
      super(message);
    }
  }

  /// `module` is a Gradle project path, `:` for the root project; `typed` is the directory as the
  /// person would type it, for the commands a failure suggests.
  static Project read(Path directory, String module, Path workDirectory, String typed) {
    if (!Files.isDirectory(directory)) {
      throw new SetupFailed("there's no directory %s".formatted(directory));
    }
    if (BUILD_FILES.stream().noneMatch(name -> Files.isRegularFile(directory.resolve(name)))) {
      throw new SetupFailed(
          """
          %s isn't a Gradle build: it has no settings.gradle(.kts) or build.gradle(.kts).
          Run mutastic on a Gradle project, naming it if it's not where you are:
            mutastic run <project directory> [:module]
            mutastic run --only=<id> <project directory> [:module]
          %s"""
              .formatted(directory, ID_HINT));
    }
    var init = workDirectory.resolve("mutastic.init.gradle.kts");
    var out = workDirectory.resolve("setup.properties");
    try {
      Files.createDirectories(workDirectory);
      try (InputStream script = GradleSetup.class.getResourceAsStream("mutastic.init.gradle.kts")) {
        Files.write(init, script.readAllBytes());
      }
      Files.deleteIfExists(out);
      var log = workDirectory.resolve("gradle.log");
      int exit =
          gradle(directory, init, out, log, (module.equals(":") ? "" : module) + ":mutasticSetup");
      if (exit != 0 || !Files.exists(out)) {
        var output = Files.readString(log).strip();
        if (output.contains("'mutasticSetup' not found")) {
          throw new SetupFailed(noJava(directory, module, workDirectory, init, typed));
        }
        throw new SetupFailed(
            "Gradle couldn't build " + directory + " for mutastic (exit " + exit + "):\n" + output);
      }
      var setup = new Properties();
      try (var in = Files.newInputStream(out)) {
        setup.load(in);
      }
      return project(directory, module, setup);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while Gradle ran", e);
    }
  }

  /// For someone who ran a command that names a mutant from somewhere else.
  private static final String ID_HINT =
      "A mutant's id belongs to the project its run was on. If yours came from "
          + "another project, run the command that run printed: it names the project's directory and module.";

  private static final List<String> BUILD_FILES =
      List.of("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle");

  /// The module asked for has no Java code: say so, and name the modules that have some.
  private static String noJava(
      Path directory, String module, Path workDirectory, Path init, String typed)
      throws IOException, InterruptedException {
    var listed = workDirectory.resolve("java-modules.txt");
    Files.deleteIfExists(listed);
    gradle(
        directory, init, listed, workDirectory.resolve("gradle-modules.log"), ":mutasticModules");
    var modules =
        Files.exists(listed)
            ? Files.readAllLines(listed).stream().filter(line -> !line.isBlank()).toList()
            : List.<String>of();
    var where =
        module.equals(":")
            ? "the root project of " + directory
            : "module %s of %s".formatted(module, directory);
    if (modules.isEmpty()) {
      return "%s has no Java code for mutastic to test, and no module of it has.%n".formatted(where)
          + "Run mutastic on the project with your Java code and tests:\n"
          + "  mutastic run <project directory> [:module]\n"
          + ID_HINT;
    }
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
        .formatted(where, String.join(", ", modules), suggestions, on, modules.getFirst(), ID_HINT);
  }

  /// Runs the build's own Gradle with mutastic's init script; its output goes to `log`.
  private static int gradle(Path directory, Path init, Path out, Path log, String task)
      throws IOException, InterruptedException {
    var command =
        List.of(
            gradle(directory),
            "-q",
            "--no-configuration-cache",
            "-I",
            init.toString(),
            "-PmutasticOut=" + out,
            task);
    try {
      return new ProcessBuilder(command)
          .directory(directory.toFile())
          .redirectOutput(log.toFile())
          .redirectErrorStream(true)
          .start()
          .waitFor();
    } catch (IOException e) {
      if (command.getFirst().equals("gradle")) {
        throw new SetupFailed(
            "%s has no Gradle wrapper (gradlew), and there's no `gradle` to run instead"
                .formatted(directory));
      }
      throw e;
    }
  }

  private static Project project(Path directory, String module, Properties setup) {
    var name =
        directory.toAbsolutePath().normalize().getFileName()
            + (module.equals(":") ? "" : module.replace(':', '/'));
    return new Project(
        name,
        Path.of(setup.getProperty("java")),
        jvmArgs(setup.getProperty("jvmArgs")),
        Path.of(setup.getProperty("workingDirectory")),
        paths(setup.getProperty("classes")),
        paths(setup.getProperty("resources")),
        paths(setup.getProperty("testClasses")),
        paths(setup.getProperty("testResources")),
        paths(setup.getProperty("sources")),
        paths(setup.getProperty("testSources")),
        paths(setup.getProperty("dependencies")));
  }

  /// The test task's JVM arguments, without a JaCoCo agent: JaCoCo adds members as classes load,
  /// and a class whose shape changed can't have mutants swapped in.
  private static List<String> jvmArgs(String lines) {
    return lines
        .lines()
        .map(String::strip)
        .filter(
            arg ->
                !arg.isEmpty() && !(arg.startsWith("-javaagent:") && arg.contains("jacocoagent")))
        .toList();
  }

  private static List<Path> paths(String joined) {
    return joined == null || joined.isBlank()
        ? List.of()
        : Arrays.stream(joined.split(File.pathSeparator)).map(Path::of).toList();
  }

  /// The project's wrapper, as its own build runs; `gradle` on the PATH without one.
  private static String gradle(Path directory) {
    var wrapper = directory.resolve(File.separatorChar == '\\' ? "gradlew.bat" : "gradlew");
    return Files.isExecutable(wrapper) ? wrapper.toAbsolutePath().toString() : "gradle";
  }
}
