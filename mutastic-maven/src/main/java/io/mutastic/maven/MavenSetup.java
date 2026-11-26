package io.mutastic.maven;

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
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/// A Maven module's setup: compiles the code and tests with `mvn test-compile` and mutastic's
/// extension loaded (`-Dmaven.ext.class.path`), which writes what Surefire's `test` would use.
/// Nothing in the build changes. A module below the build compiles with what it depends on (`-pl
/// <module> -am`).
final class MavenSetup {
  static final String EXTENSION_CLASS = "io/mutastic/maven/extension/MutasticSetup.class";

  private MavenSetup() {}

  /// `directory` for `:`, else the module's own: `directory/services/api` for `:services/api`.
  static Path moduleDirectory(Path directory, String module) {
    return module.equals(":") ? directory : directory.resolve(module.substring(1));
  }

  /// The test suites a Maven build has: Surefire's, and Failsafe's, by its phase or as Gradle would
  /// name it.
  static final List<String> SUITES = List.of("test", "integration-test", "integrationTest");

  /// What reading the request's module builds, and the log Maven's output goes to.
  static BuildTool.Building building(BuildTool.Request request) {
    return new BuildTool.Building(
        "mvn test-compile", log(request.workDirectory()), note(request.directory()));
  }

  /// Which Maven runs, when it isn't the build's wrapper.
  private static Optional<String> note(Path directory) {
    try {
      var tool = mvn(directory, path());
      return tool.onPath() ? Optional.of(onPath(tool)) : Optional.empty();
    } catch (SetupFailed e) {
      return Optional.empty(); // reading the build says so
    }
  }

  /// `no Maven wrapper (mvnw) here or above: running Maven 3.9.11 from the PATH,
  /// /opt/maven/bin/mvn`.
  static String onPath(Wrappers.Tool tool) {
    return "no Maven wrapper (mvnw) here or above: running %s from the PATH, %s"
        .formatted(version(tool).map(version -> "Maven " + version).orElse("Maven"), tool.file());
  }

  /// The Maven on the PATH's version, from its distribution's jars.
  private static Optional<String> version(Wrappers.Tool tool) {
    return Wrappers.version(tool.file(), CORE);
  }

  /// A Maven distribution's core, `maven-core-3.9.11.jar`.
  private static final Pattern CORE =
      Pattern.compile("maven-core-(?<version>\\d+(?:\\.\\d+)*(?:-[\\w.-]*)?)\\.jar");

  /// Why a build run with the Maven on the PATH likely failed: it may not be the Maven the build
  /// was made for.
  private static Optional<String> likely(Path directory) {
    var tool = mvn(directory, path());
    if (!tool.onPath()) {
      return Optional.empty();
    }
    return Optional.of(
        ("It ran with %s from the PATH, %s, as the build has no wrapper (mvnw) here or above. A"
                + " build made for another Maven fails this way: add the wrapper it expects, or"
                + " put the Maven it needs first on the PATH.")
            .formatted(
                version(tool).map(version -> "Maven " + version).orElse("the Maven"), tool.file()));
  }

  /// The PATH variable's value.
  private static String path() {
    return Objects.requireNonNullElse(System.getenv("PATH"), "");
  }

  private static Path log(Path workDirectory) {
    return workDirectory.resolve("maven.log");
  }

  static Project read(BuildTool.Request request, BuildArguments arguments) {
    var directory = request.directory();
    var module = request.module();
    try {
      Files.createDirectories(request.workDirectory());
      var out = request.workDirectory().resolve("maven-setup");
      var log = log(request.workDirectory());
      if (mvn(directory, command(request, out, arguments), log) != 0) {
        throw failed(request, log, arguments);
      }
      var setup =
          out.resolve(module.equals(":") ? "" : module.substring(1)).resolve("setup.properties");
      if (!Files.isRegularFile(setup)) {
        throw new SetupFailed("module-without-java", noJava(request, arguments));
      }
      return written(directory, module, setup);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while Maven ran", e);
    }
  }

  /// Why the build failed, from its log: a module the reactor hasn't got, a JDK its toolchain asks
  /// for that `~/.m2/toolchains.xml` hasn't, a suite the module hasn't, else the log to read.
  private static SetupFailed failed(BuildTool.Request request, Path log, BuildArguments arguments)
      throws IOException, InterruptedException {
    var directory = request.directory();
    var module = request.module();
    var output = Files.readAllLines(log);
    if (output.stream().anyMatch(line -> line.contains(NOT_IN_REACTOR))) {
      return new SetupFailed(
          "module-missing",
          Modules.missing(module, javaModules(directory, arguments), request.typed()));
    }
    var toolchain = MavenToolchain.missing(output);
    if (toolchain.isPresent()) {
      return noToolchain(request, arguments, toolchain.get(), log);
    }
    var built = built(request, arguments);
    return SetupFailed.noSuite(output.stream())
        .orElseGet(
            () ->
                new SetupFailed(
                    "build-failed",
                    BuildErrors.failed(
                        new BuildErrors.Build("Maven", name(directory, module), log, built),
                        output,
                        likely(directory))));
  }

  /// The build's toolchain asks for a JDK `~/.m2/toolchains.xml` has none of: the run again, with a
  /// toolchains file that has one.
  private static SetupFailed noToolchain(
      BuildTool.Request request, BuildArguments arguments, BuildErrors.Toolchain jdk, Path log) {
    var run =
        BuildErrors.run(request.typed(), request.module(), arguments, MavenToolchain.ARGUMENT);
    return new SetupFailed(
        "build-jdk",
        BuildErrors.noToolchain(
            "Maven",
            new BuildErrors.Missing(jdk, MavenToolchain.WHERE),
            "Add it to ~/.m2/toolchains.xml, or tell Maven which toolchains file has it:",
            run,
            arguments,
            log));
  }

  /// The command that builds what mutastic built, as the person would type it.
  private static String built(BuildTool.Request request, BuildArguments arguments) {
    var directory = request.directory();
    var rest = new ArrayList<>(List.of("test-compile"));
    if (!request.module().equals(":")) {
      rest.addAll(List.of("-pl", request.module().substring(1), "-am"));
    }
    return BuildErrors.command(mvn(directory).command(), directory, "-f", rest)
        + arguments.buildEnding();
  }

  /// `mvn test -Dtest='PricingTest#bigOrders'`, or Failsafe's `verify -Dit.test=` for its suite;
  /// with `-pl` for a module, whose build makes what it uses first, and lets those have no such
  /// test. Surefire names a test by its class's simple name and its method.
  static String testCommand(BuildTool.Request request, String test, BuildArguments arguments) {
    var directory = request.directory();
    var module = request.module();
    boolean failsafe = !request.suite().equals(BuildTool.DEFAULT_SUITE);
    var named = test.replaceFirst("\\(.*$", "");
    int hash = named.indexOf('#');
    var className = hash < 0 ? named : named.substring(0, hash);
    var simple =
        className.substring(className.lastIndexOf('.') + 1) + named.substring(className.length());
    var rest = new ArrayList<>(List.of(failsafe ? "verify" : "test"));
    if (!module.equals(":")) {
      rest.addAll(
          List.of(
              "-pl",
              module.substring(1),
              "-am",
              failsafe
                  ? "-Dfailsafe.failIfNoSpecifiedTests=false"
                  : "-Dsurefire.failIfNoSpecifiedTests=false"));
    }
    rest.add((failsafe ? "-Dit.test=" : "-Dtest=") + "'" + simple + "'");
    return BuildErrors.command(mvn(directory).command(), directory, "-f", rest)
        + arguments.buildEnding();
  }

  /// What Maven says when `-pl` names a module its reactor hasn't got.
  private static final String NOT_IN_REACTOR = "Could not find the selected project in the reactor";

  /// A module whose directory has no `pom.xml` isn't one of the build's, unless the reactor lists
  /// it (a module can name a pom file of another name): said before its work directory is made, so
  /// a mistyped name leaves no directory behind.
  static void checkModule(Path directory, String module, String typed, BuildArguments arguments) {
    if (module.equals(":")
        || Files.isRegularFile(moduleDirectory(directory, module).resolve("pom.xml"))) {
      return;
    }
    try {
      var modules = javaModules(directory, arguments);
      if (!modules.contains(module)) {
        throw new SetupFailed("module-missing", Modules.missing(module, modules, typed));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while Maven ran", e);
    }
  }

  /// The build's modules with Java code, `:` for the build's own directory, as its reactor lists
  /// them (a `validate` run, so quick). Its output goes to the build's own work directory.
  private static List<String> javaModules(Path directory, BuildArguments arguments)
      throws IOException, InterruptedException {
    var work = moduleDirectory(directory, ":").resolve("target/mutastic");
    Files.createDirectories(work);
    var listed = work.resolve("java-modules.txt");
    Files.deleteIfExists(listed);
    var command =
        commandOf(
            directory,
            arguments,
            "-q",
            "-B",
            "validate",
            "-Dmaven.ext.class.path=" + extensionJar(),
            "-Dmutastic.modules=" + listed);
    mvn(directory, command, work.resolve("maven-modules.log"));
    return Files.exists(listed)
        ? Files.readAllLines(listed).stream()
            .filter(line -> !line.isBlank())
            .map(path -> path.equals(".") ? ":" : ":" + path)
            .toList()
        : List.of();
  }

  /// The module's setup, already written to `setup`: by the extension, or by the Maven plugin.
  static Project written(Path directory, String module, Path setup) {
    try {
      return ProjectSetup.read(name(directory, module), setup);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// The modules that depend on `module`, from the reactor's graph (a `validate` run, so quick).
  static List<String> dependents(
      Path directory, String module, Path workDirectory, BuildArguments arguments) {
    try {
      Files.createDirectories(workDirectory);
      var listed = workDirectory.resolve("dependents.txt");
      Files.deleteIfExists(listed);
      var log = workDirectory.resolve("maven-dependents.log");
      var command = dependentsCommand(directory, module, listed, arguments);
      if (mvn(directory, command, log) != 0 || !Files.exists(listed)) {
        throw new SetupFailed(
            "build-failed",
            "Maven couldn't list the modules that depend on %s; its output is in %s"
                .formatted(module, log));
      }
      return Files.readAllLines(listed).stream()
          .filter(line -> !line.isBlank())
          .map(path -> ":" + path)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while Maven ran", e);
    }
  }

  /// `mvn validate` with the extension asked for `module`'s dependents, into `listed`.
  private static List<String> dependentsCommand(
      Path directory, String module, Path listed, BuildArguments arguments) {
    return commandOf(
        directory,
        arguments,
        "-q",
        "-B",
        "validate",
        "-Dmaven.ext.class.path=" + extensionJar(),
        "-Dmutastic.dependents=" + listed,
        "-Dmutastic.module=" + (module.equals(":") ? "" : module.substring(1)));
  }

  /// Not `-q`: the log says what Maven is doing, for progress to show as it runs.
  private static List<String> command(
      BuildTool.Request request, Path out, BuildArguments arguments) {
    var module = request.module();
    var command =
        new ArrayList<>(
            commandOf(
                request.directory(),
                arguments,
                "-B",
                "test-compile",
                "-Dmaven.ext.class.path=" + extensionJar(),
                "-Dmutastic.out=" + out,
                "-Dmutastic.suite=" + request.suite()));
    if (!module.equals(":")) {
      command.addAll(List.of("-pl", module.substring(1), "-am"));
    }
    return command;
  }

  private static int mvn(Path directory, List<String> command, Path log)
      throws IOException, InterruptedException {
    var builder =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    // On the JDK the build was given, not mutastic's.
    BuildJava.restore(builder.environment());
    return builder.start().waitFor();
  }

  private static Wrappers.Tool mvn(Path directory) {
    return mvn(directory, path());
  }

  /// The build's Maven, the person's arguments, then mutastic's own, `rest`: of a property set
  /// twice, Maven reads the last.
  private static List<String> commandOf(Path directory, BuildArguments arguments, String... rest) {
    var command = new ArrayList<>(mvn(directory).command());
    command.addAll(arguments.words());
    command.addAll(List.of(rest));
    return List.copyOf(command);
  }

  /// The build's wrapper, as its own build runs: the nearest from `directory` up to the top of its
  /// build, where `.mvn` is, so a module directory runs its build's; or above that, in the same
  /// repository. Else `mvn` on `path`, the PATH.
  static Wrappers.Tool mvn(Path directory, String path) {
    var root = Wrappers.root(directory, at -> Files.isDirectory(at.resolve(".mvn")));
    var wrapper = Wrappers.wrapper(directory, root, Wrappers.WINDOWS ? "mvnw.cmd" : "mvnw");
    return Wrappers.tool(wrapper, "mvn", path)
        .orElseThrow(
            () ->
                new SetupFailed(
                    "no-build-tool",
                    "%s has no Maven wrapper (mvnw), and there's no `mvn` to run instead"
                        .formatted(directory)));
  }

  /// The module asked for has no Java code: say so, and name the modules that have some.
  private static String noJava(BuildTool.Request request, BuildArguments arguments)
      throws IOException, InterruptedException {
    var directory = request.directory();
    var module = request.module();
    var modules = javaModules(directory, arguments);
    return module.equals(":")
        ? Modules.atRoot(directory, modules, request.typed())
        : Modules.withoutJava(directory, module, modules, request.typed());
  }

  /// `pricing`, or `app/services/api` for `:services/api`.
  private static String name(Path directory, String module) {
    return directory.toAbsolutePath().normalize().getFileName()
        + (module.equals(":") ? "" : "/" + module.substring(1));
  }

  /// The extension's jar, shipped beside this one. Found by its class file, not by loading the
  /// class: its superclass is Maven's, which only the build has.
  private static Path extensionJar() {
    var classFile = MavenSetup.class.getClassLoader().getResource(EXTENSION_CLASS);
    if (classFile == null || !classFile.getProtocol().equals("jar")) {
      throw new IllegalStateException(
          "mutastic's Maven extension is missing from its installation");
    }
    var jar = classFile.getPath().substring(0, classFile.getPath().indexOf("!/"));
    try {
      return Path.of(new java.net.URI(jar));
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
