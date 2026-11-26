package io.mutastic.gradle;

import io.mutastic.api.BuildArguments;
import io.mutastic.api.BuildErrors;
import io.mutastic.api.BuildJava;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/// A Gradle module's setup, read by running its build with mutastic's init script: compiles the
/// code and tests, and writes what the `test` task would use.
final class GradleSetup {
  private GradleSetup() {}

  /// The request's module is a Gradle project path, `:` for the root project; its suite the `Test`
  /// task to read.
  static Project read(BuildTool.Request request, BuildArguments arguments) {
    var build =
        new Build(
            request.directory(),
            request.workDirectory().resolve("mutastic.init.gradle.kts"),
            request.suite(),
            arguments);
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
    return new BuildTool.Building(
        "gradle " + module + classes, log(request.workDirectory()), note(request.directory()));
  }

  /// Which Gradle runs, when it isn't the build's wrapper: said before it builds, as a Gradle other
  /// than the one the build was made for may not read it.
  private static Optional<String> note(Path directory) {
    try {
      var tool = gradle(directory, path());
      return tool.onPath() ? Optional.of(onPath(tool)) : Optional.empty();
    } catch (SetupFailed e) {
      return Optional.empty(); // reading the build says so
    }
  }

  /// `no Gradle wrapper (gradlew) here or above: running Gradle 8.2.1 from the PATH,
  /// /opt/gradle/bin/gradle`.
  static String onPath(Wrappers.Tool tool) {
    return "no Gradle wrapper (gradlew) here or above: running %s from the PATH, %s"
        .formatted(version(tool).map(version -> "Gradle " + version).orElse("Gradle"), tool.file());
  }

  /// The Gradle `tool` runs: its wrapper's, or the PATH's, from its distribution's jars.
  static Optional<GradleJdks.Version> version(Wrappers.Tool tool) {
    if (!tool.onPath()) {
      return GradleJdks.wrapped(tool.file());
    }
    return Wrappers.version(tool.file(), LAUNCHER)
        .flatMap(
            version -> {
              try {
                return Optional.of(GradleJdks.Version.of(version));
              } catch (IllegalArgumentException e) {
                return Optional.empty();
              }
            });
  }

  /// A Gradle distribution's launcher, `gradle-launcher-8.2.1.jar`, by its version's numbers.
  private static final Pattern LAUNCHER =
      Pattern.compile("gradle-launcher-(?<version>\\d+(?:\\.\\d+)*)(?:-[\\w.-]*)?\\.jar");

  /// The PATH variable's value.
  private static String path() {
    return Objects.requireNonNullElse(System.getenv("PATH"), "");
  }

  private static Path log(Path workDirectory) {
    return workDirectory.resolve("gradle.log");
  }

  /// A build to run, mutastic's init script to run it with, and the `Test` task it reads.
  private record Build(Path directory, Path init, String suite, BuildArguments arguments) {
    Build(Path directory, Path init, BuildArguments arguments) {
      this(directory, init, BuildTool.DEFAULT_SUITE, arguments);
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
      // A Gradle too old for the build's JDK stops before it reads the build, in words that don't
      // say why.
      var tooOld = tooOldFor(build, request);
      throw tooOld.isPresent() ? tooOld.get() : failed(build, request);
    }
    return ProjectSetup.read(name(build.directory(), module), out);
  }

  /// Why the build failed, from its log: a module with no Java code, a module the build hasn't got,
  /// a JDK its toolchain asks for that Gradle found none of, a suite the module hasn't, else
  /// Gradle's own output.
  private static SetupFailed failed(Build build, BuildTool.Request request)
      throws IOException, InterruptedException {
    var module = request.module();
    var workDirectory = request.workDirectory();
    var typed = request.typed();
    var output = said(Files.readString(log(workDirectory)));
    if (output.contains("'mutasticSetup' not found")) {
      return new SetupFailed("module-without-java", noJava(build, module, workDirectory, typed));
    }
    if (NOT_FOUND.matcher(output).find() || output.contains("mutastic: no project ")) {
      return new SetupFailed(
          "module-missing", Modules.missing(module, javaModules(build, workDirectory), typed));
    }
    var toolchain = GradleToolchain.missing(output);
    if (toolchain.isPresent()) {
      return noToolchain(build, request, toolchain.get());
    }
    return SetupFailed.noSuite(output.lines()).orElseGet(() -> buildFailed(build, request, output));
  }

  /// The build's toolchain asks for a JDK Gradle found none of: the run again, telling Gradle where
  /// one is.
  private static SetupFailed noToolchain(
      Build build, BuildTool.Request request, BuildErrors.Toolchain jdk) {
    var run =
        BuildErrors.run(
            request.typed(), request.module(), build.arguments(), GradleToolchain.argument(jdk));
    return new SetupFailed(
        "build-jdk",
        BuildErrors.noToolchain(
            "Gradle",
            new BuildErrors.Missing(jdk, ""),
            "Install one, or tell Gradle where it is:",
            run,
            build.arguments(),
            log(request.workDirectory())));
  }

  /// The build failed: Gradle's errors, and the Gradle on the PATH when that's likely why.
  private static SetupFailed buildFailed(Build build, BuildTool.Request request, String output) {
    return new SetupFailed(
        "build-failed",
        BuildErrors.failed(
            new BuildErrors.Build(
                "Gradle",
                name(build.directory(), request.module()),
                log(request.workDirectory()),
                built(build.directory(), request) + build.arguments().buildEnding()),
            output.lines().toList(),
            likely(build.directory(), path())));
  }

  /// Why a build run with the Gradle on the PATH likely failed: it may not be the Gradle the build
  /// was made for. `path` is the PATH.
  static Optional<String> likely(Path directory, String path) {
    var tool = gradle(directory, path);
    if (!tool.onPath()) {
      return Optional.empty();
    }
    return Optional.of(
        ("It ran with %s from the PATH, %s, as the build has no wrapper (gradlew) here or above. A"
                + " build made for another Gradle fails this way: add the wrapper it expects, or"
                + " put the Gradle it needs first on the PATH.")
            .formatted(
                version(tool).map(version -> "Gradle " + version).orElse("the Gradle"),
                tool.file()));
  }

  /// Said when the build's Gradle can't run on its JDK: which is why it failed.
  private static Optional<SetupFailed> tooOldFor(Build build, BuildTool.Request request) {
    var directory = build.directory();
    return tooOld(directory, BuildJava.environment(), path())
        .map(
            tooOld -> {
              var tool = gradle(directory, path());
              var failed =
                  new BuildErrors.Build(
                      "Gradle",
                      name(directory, request.module()),
                      log(request.workDirectory()),
                      GradleJdks.upgrade(tooOld, tool.command(), directory));
              return new SetupFailed("build-jdk", GradleJdks.said(tooOld, failed, tool.onPath()));
            });
  }

  /// The build's Gradle, its wrapper's or the PATH's, when it can't run on the JDK a build with
  /// `environment` runs on: asked only once the build has failed, as a Gradle may run on a JDK
  /// newer than it supports. `path` is the PATH.
  static Optional<GradleJdks.TooOld> tooOld(
      Path directory, Map<String, String> environment, String path) {
    Optional<GradleJdks.Version> gradle;
    try {
      gradle = version(gradle(directory, path));
    } catch (SetupFailed e) {
      gradle = Optional.empty();
    }
    var jdk = BuildJava.feature(environment);
    if (gradle.isEmpty() || jdk.isEmpty()) {
      return Optional.empty();
    }
    return GradleJdks.tooOld(gradle.get(), jdk.getAsInt());
  }

  /// `./gradlew :core:test --tests 'com.example.PricingTest.bigOrders'`: the suite's task, filtered
  /// to the test, which Gradle names by its class and method, without parameters.
  static String testCommand(BuildTool.Request request, String test, BuildArguments arguments) {
    var directory = request.directory();
    var program = gradle(directory, path()).command();
    var module = request.module().equals(":") ? "" : request.module() + ":";
    var filter = test.replaceFirst("\\(.*$", "").replace('#', '.');
    return BuildErrors.command(
            program,
            directory,
            "-p",
            List.of(module + request.suite(), "--tests", "'" + filter + "'"))
        + arguments.buildEnding();
  }

  /// The command that builds what mutastic built, as the person would type it: `./gradlew
  /// :core:testClasses`.
  private static String built(Path directory, BuildTool.Request request) {
    var program = gradle(directory, path()).command();
    var task = building(request).task().substring("gradle ".length());
    return BuildErrors.command(program, directory, "-p", List.of(task));
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
  static List<String> dependents(
      Path directory, String module, Path workDirectory, BuildArguments arguments) {
    var build = new Build(directory, workDirectory.resolve("mutastic.init.gradle.kts"), arguments);
    try {
      Files.createDirectories(workDirectory);
      writeInitScript(build.init());
      var listed = workDirectory.resolve("dependents.txt");
      Files.deleteIfExists(listed);
      var task = task(directory, module, "mutasticDependents");
      if (gradle(build, listed, workDirectory.resolve("gradle-dependents.log"), task) != 0) {
        throw new SetupFailed(
            "build-failed",
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

  /// Runs the build's own Gradle with mutastic's init script, on the JDK the build was given; its
  /// output goes to `log`, the tasks it runs among it, so that progress can show the latest.
  private static int gradle(Build build, Path out, Path log, String task)
      throws IOException, InterruptedException {
    var builder =
        new ProcessBuilder(gradleCommand(build, out, task))
            .directory(build.directory().toFile())
            .redirectOutput(log.toFile())
            .redirectErrorStream(true);
    BuildJava.restore(builder.environment());
    return builder.start().waitFor();
  }

  private static List<String> gradleCommand(Build build, Path out, String task) {
    var command = new java.util.ArrayList<>(gradle(build.directory(), path()).command());
    // First, so mutastic's own, which read the last of two, win.
    command.addAll(build.arguments().words());
    command.addAll(
        List.of(
            "--console=plain",
            "--no-configuration-cache",
            "-I",
            build.init().toString(),
            "-PmutasticOut=" + out,
            "-PmutasticSuite=" + build.suite(),
            "-PmutasticProject=" + project(task),
            task));
    return command;
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
  /// build, where its settings file is, so a module directory runs its build's; or above that, in
  /// the same repository, for a build nested in another. Else `gradle` on `path`, the PATH.
  static Wrappers.Tool gradle(Path directory, String path) {
    var root = Wrappers.root(directory, GradleSetup::hasSettings);
    var wrapper = Wrappers.wrapper(directory, root, Wrappers.WINDOWS ? "gradlew.bat" : "gradlew");
    return Wrappers.tool(wrapper, "gradle", path)
        .orElseThrow(
            () ->
                new SetupFailed(
                    "no-build-tool",
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
