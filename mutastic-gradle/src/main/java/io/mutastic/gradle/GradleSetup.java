package io.mutastic.gradle;

import io.mutastic.api.BuildTool;
import io.mutastic.api.BuildTool.SetupFailed;
import io.mutastic.api.Project;
import io.mutastic.api.ProjectSetup;
import io.mutastic.api.Wrappers;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// A Gradle module's setup, read by running its build with mutastic's init script: compiles the
/// code and tests, and writes what the `test` task would use.
final class GradleSetup {
  private GradleSetup() {}

  /// The request's module is a Gradle project path, `:` for the root project; its suite the `Test`
  /// task to read.
  static Project read(BuildTool.Request request) {
    var build =
        new Build(
            request.directory(),
            request.workDirectory().resolve("mutastic.init.gradle.kts"),
            request.suite());
    try {
      return setUp(build, request.module(), request.workDirectory(), request.typed());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while Gradle ran", e);
    }
  }

  /// A build to run, mutastic's init script to run it with, and the `Test` task it reads.
  private record Build(Path directory, Path init, String suite) {
    Build(Path directory, Path init) {
      this(directory, init, BuildTool.DEFAULT_SUITE);
    }
  }

  /// Runs the module's `mutasticSetup` task and reads the project from what it wrote.
  private static Project setUp(Build build, String module, Path workDirectory, String typed)
      throws IOException, InterruptedException {
    var out = workDirectory.resolve("setup.properties");
    Files.createDirectories(workDirectory);
    writeInitScript(build.init());
    Files.deleteIfExists(out);
    var log = workDirectory.resolve("gradle.log");
    int exit = gradle(build, out, log, task(build.directory(), module, "mutasticSetup"));
    if (exit != 0 || !Files.exists(out)) {
      var output = Files.readString(log).strip();
      if (output.contains("'mutasticSetup' not found")) {
        throw new SetupFailed(noJava(build, module, workDirectory, typed));
      }
      throw SetupFailed.noSuite(output.lines())
          .orElseGet(
              () ->
                  new SetupFailed(
                      "Gradle couldn't build "
                          + build.directory()
                          + " for mutastic (exit "
                          + exit
                          + "):\n"
                          + output));
    }
    return ProjectSetup.read(name(build.directory(), module), out);
  }

  /// The module's setup, already written to `setup`.
  static Project written(Path directory, String module, Path setup) {
    try {
      return ProjectSetup.read(name(directory, module), setup);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
      return "%s has no Java code for mutastic to test, and no module of it has.\n".formatted(where)
          + "Run mutastic on the project with your Java code and tests:\n"
          + "  mutastic run <project directory> [:module]\n"
          + SetupFailed.ID_HINT;
    }
    return javaElsewhere(where, modules, typed);
  }

  /// The modules that depend on `module`, as its `mutasticDependents` task lists them.
  static List<String> dependents(Path directory, String module, Path workDirectory) {
    var build = new Build(directory, workDirectory.resolve("mutastic.init.gradle.kts"));
    try {
      Files.createDirectories(workDirectory);
      writeInitScript(build.init());
      var listed = workDirectory.resolve("dependents.txt");
      Files.deleteIfExists(listed);
      var task = task(directory, module, "mutasticDependents");
      if (gradle(build, listed, workDirectory.resolve("gradle-dependents.log"), task) != 0) {
        throw new SetupFailed(
            "Gradle couldn't list the modules that depend on %s; its output is in %s"
                .formatted(module, workDirectory.resolve("gradle-dependents.log")));
      }
      return Files.readAllLines(listed).stream().filter(line -> !line.isBlank()).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while Gradle ran", e);
    }
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
    return new ProcessBuilder(gradleCommand(build, out, task))
        .directory(build.directory().toFile())
        .redirectOutput(log.toFile())
        .redirectErrorStream(true)
        .start()
        .waitFor();
  }

  private static List<String> gradleCommand(Build build, Path out, String task) {
    return List.of(
        gradle(build.directory(), Objects.requireNonNullElse(System.getenv("PATH"), "")),
        "-q",
        "--no-configuration-cache",
        "-I",
        build.init().toString(),
        "-PmutasticOut=" + out,
        "-PmutasticSuite=" + build.suite(),
        task);
  }

  /// `pricing`, or `app/data` for `:data`.
  private static String name(Path directory, String module) {
    return directory.toAbsolutePath().normalize().getFileName()
        + (module.equals(":") ? "" : module.replace(':', '/'));
  }

  /// The build's wrapper, as its own build runs: the nearest from `directory` up to the top of its
  /// build, where its settings file is, so a module directory runs its build's. Else `gradle` on
  /// `path`, the PATH.
  static String gradle(Path directory, String path) {
    var root = Wrappers.root(directory, GradleSetup::hasSettings);
    var found = Wrappers.wrapper(directory, root, Wrappers.WINDOWS ? "gradlew.bat" : "gradlew");
    if (found.isPresent()) {
      return found.get().toString();
    }
    return Wrappers.program("gradle", path)
        .orElseThrow(
            () ->
                new SetupFailed(
                    "%s has no Gradle wrapper (gradlew), and there's no `gradle` to run instead"
                        .formatted(directory)));
  }

  /// `module`'s task by its full Gradle path: `:core:mutasticSetup` for the build in a module
  /// directory, `core`, since Gradle runs the whole build from any directory in it, and a path from
  /// its top. A module is where Gradle keeps it by default, its path's directories.
  static String task(Path directory, String module, String task) {
    var root = Wrappers.root(directory, GradleSetup::hasSettings);
    var below = root.relativize(directory.toAbsolutePath().normalize());
    var path = new StringBuilder();
    for (var name : below) {
      if (!name.toString().isEmpty()) {
        path.append(':').append(name);
      }
    }
    return path + (module.equals(":") ? "" : module) + ":" + task;
  }

  private static boolean hasSettings(Path directory) {
    return BUILD_FILES.stream()
        .filter(name -> name.startsWith("settings"))
        .anyMatch(name -> Files.isRegularFile(directory.resolve(name)));
  }
}
