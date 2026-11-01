package io.mutastic.cli;

import io.mutastic.engine.Project;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/// A Gradle module's setup, read by running its build with mutastic's init script: compiles the
/// code and tests, and writes what the `test` task would use.
final class GradleSetup {
  private GradleSetup() {}

  /// `module` is a Gradle project path, `:` for the root project.
  static Project read(Path directory, String module, Path workDirectory) {
    var init = workDirectory.resolve("mutastic.init.gradle.kts");
    var out = workDirectory.resolve("setup.properties");
    try {
      Files.createDirectories(workDirectory);
      try (InputStream script = GradleSetup.class.getResourceAsStream("mutastic.init.gradle.kts")) {
        Files.write(init, script.readAllBytes());
      }
      Files.deleteIfExists(out);
      var command = new ArrayList<>(List.of(gradle(directory), "-q", "--no-configuration-cache",
          "-I", init.toString(), "-PmutasticOut=" + out, (module.equals(":") ? "" : module) + ":mutasticSetup"));
      var log = workDirectory.resolve("gradle.log");
      int exit = new ProcessBuilder(command)
          .directory(directory.toFile())
          .redirectOutput(log.toFile())
          .redirectErrorStream(true)
          .start()
          .waitFor();
      if (exit != 0 || !Files.exists(out)) {
        throw new IllegalStateException("Gradle couldn't build " + directory + " for mutastic (exit " + exit + "):\n"
            + Files.readString(log).strip());
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

  private static Project project(Path directory, String module, Properties setup) {
    var name = directory.toAbsolutePath().normalize().getFileName() + (module.equals(":") ? "" : module.replace(':', '/'));
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
        paths(setup.getProperty("dependencies")));
  }

  /// The test task's JVM arguments, without a JaCoCo agent: JaCoCo adds members as classes
  /// load, and a class whose shape changed can't have mutants swapped in.
  private static List<String> jvmArgs(String lines) {
    return lines.lines()
        .map(String::strip)
        .filter(arg -> !arg.isEmpty() && !(arg.startsWith("-javaagent:") && arg.contains("jacocoagent")))
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
