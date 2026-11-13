package io.mutastic.gradle;

import io.mutastic.api.BuildTool;
import io.mutastic.api.BuildTool.SetupFailed;
import io.mutastic.api.Modules;
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
import java.util.regex.Pattern;

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
      return setUp(build, request);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while Gradle ran", e);
    }
  }

  /// What reading the request's module builds: its suite's classes, by the task a person would run
  /// for them (`testClasses`, `:app:testClasses`), and the log Gradle's output goes to.
  static BuildTool.Building building(BuildTool.Request request) {
    var module = request.module().equals(":") ? "" : request.module() + ":";
    var classes =
        request.suite().equals(BuildTool.DEFAULT_SUITE)
            ? "testClasses"
            : request.suite() + "Classes";
    return new BuildTool.Building("gradle " + module + classes, log(request.workDirectory()));
  }

  private static Path log(Path workDirectory) {
    return workDirectory.resolve("gradle.log");
  }

  /// A build to run, mutastic's init script to run it with, and the `Test` task it reads.
  private record Build(Path directory, Path init, String suite) {
    Build(Path directory, Path init) {
      this(directory, init, BuildTool.DEFAULT_SUITE);
    }
  }

  /// Runs the module's `mutasticSetup` task and reads the project from what it wrote.
  private static Project setUp(Build build, BuildTool.Request request)
      throws IOException, InterruptedException {
    var module = request.module();
    var workDirectory = request.workDirectory();
    var out = workDirectory.resolve("setup.properties");
    Files.createDirectories(workDirectory);
    writeInitScript(build.init());
    Files.deleteIfExists(out);
    int exit =
        gradle(build, out, log(workDirectory), task(build.directory(), module, "mutasticSetup"));
    if (exit != 0 || !Files.exists(out)) {
      throw failed(build, request, exit);
    }
    return ProjectSetup.read(name(build.directory(), module), out);
  }

  /// Why the build failed, from its log: a module with no Java code, a module the build hasn't got,
  /// a suite the module hasn't, else Gradle's own output.
  private static SetupFailed failed(Build build, BuildTool.Request request, int exit)
      throws IOException, InterruptedException {
    var module = request.module();
    var workDirectory = request.workDirectory();
    var typed = request.typed();
    var output = said(Files.readString(log(workDirectory)));
    if (output.contains("'mutasticSetup' not found")) {
      return new SetupFailed(noJava(build, module, workDirectory, typed));
    }
    if (NOT_FOUND.matcher(output).find() || output.contains("mutastic: no project ")) {
      return new SetupFailed(Modules.missing(module, javaModules(build, workDirectory), typed));
    }
    return SetupFailed.noSuite(output.lines())
        .orElseGet(
            () ->
                new SetupFailed(
                    "Gradle couldn't build %s for mutastic (exit %d):\n%s"
                        .formatted(build.directory(), exit, output)));
  }

  /// What Gradle said beyond its progress: the log as `-q` would have kept it, without the tasks it
  /// ran and how the build went, which are written to the log for progress to show as it runs.
  static String said(String log) {
    return log.lines()
        .filter(line -> PROGRESS.stream().noneMatch(line::startsWith))
        .filter(line -> !line.contains(" actionable task"))
        .collect(java.util.stream.Collectors.joining("\n"))
        .strip();
  }

  /// How Gradle's lifecycle lines start, which `-q` leaves out.
  private static final List<String> PROGRESS =
      List.of(
          "> Task ",
          "> Configure project ",
          "BUILD SUCCESSFUL in ",
          "BUILD FAILED in ",
          "Starting a Gradle Daemon",
          "Daemon will be stopped at the end of the build",
          "[Incubating] Problems report is available at",
          "Deprecated Gradle features were used in this build",
          "You can use '--warning-mode all' to show the individual deprecation warnings",
          "For more on this, please refer to https://docs.gradle.org");

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

  /// What Gradle says when a task's path names a project the build hasn't got. A name only the
  /// start of a project's, Gradle would take for that project: the init script says so instead.
  private static final Pattern NOT_FOUND =
      Pattern.compile("as project '(?<project>[^']*)' not found in");

  static final List<String> BUILD_FILES =
      List.of("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle");

  /// The module asked for has no Java code: say so, and name the modules that have some.
  private static String noJava(Build build, String module, Path workDirectory, String typed)
      throws IOException, InterruptedException {
    var modules = javaModules(build, workDirectory);
    return module.equals(":")
        ? Modules.atRoot(build.directory(), modules, typed)
        : Modules.withoutJava(build.directory(), module, modules, typed);
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

  /// Runs the build's own Gradle with mutastic's init script; its output goes to `log`, the tasks
  /// it runs among it, so that progress can show the latest.
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
        "--console=plain",
        "--no-configuration-cache",
        "-I",
        build.init().toString(),
        "-PmutasticOut=" + out,
        "-PmutasticSuite=" + build.suite(),
        "-PmutasticProject=" + project(task),
        task);
  }

  /// The project a task's path names: `:core` for `:core:mutasticSetup`, `:` for the root's.
  private static String project(String task) {
    var project = task.substring(0, task.lastIndexOf(':'));
    return project.isEmpty() ? ":" : project;
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
