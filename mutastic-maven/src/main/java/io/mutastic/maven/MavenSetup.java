package io.mutastic.maven;

import io.mutastic.api.BuildTool;
import io.mutastic.api.BuildTool.SetupFailed;
import io.mutastic.api.Project;
import io.mutastic.api.ProjectSetup;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// A Maven module's setup: compiles the code and tests with `mvn test-compile` and mutastic's
/// extension loaded (`-Dmaven.ext.class.path`), which writes what Surefire's `test` would use.
/// Nothing in the build changes. A module below the build compiles with what it depends on (`-pl
/// <module> -am`).
final class MavenSetup {
  private static final String EXTENSION_CLASS = "io/mutastic/maven/extension/MutasticSetup.class";

  private MavenSetup() {}

  /// `directory` for `:`, else the module's own: `directory/services/api` for `:services/api`.
  static Path moduleDirectory(Path directory, String module) {
    return module.equals(":") ? directory : directory.resolve(module.substring(1));
  }

  /// The test suites a Maven build has: Surefire's, and Failsafe's, by its phase or as Gradle would
  /// name it.
  static final List<String> SUITES = List.of("test", "integration-test", "integrationTest");

  static Project read(BuildTool.Request request) {
    var directory = request.directory();
    var module = request.module();
    try {
      Files.createDirectories(request.workDirectory());
      var out = request.workDirectory().resolve("maven-setup");
      var log = request.workDirectory().resolve("maven.log");
      if (mvn(directory, command(directory, module, out, request.suite()), log) != 0) {
        throw SetupFailed.noSuite(Files.readAllLines(log).stream())
            .orElseGet(() -> new SetupFailed("the Maven build failed; its output is in " + log));
      }
      var setup =
          out.resolve(module.equals(":") ? "" : module.substring(1)).resolve("setup.properties");
      if (!Files.isRegularFile(setup)) {
        throw new SetupFailed(noJava(directory, module, request.typed()));
      }
      return written(directory, module, setup);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while Maven ran", e);
    }
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
  static List<String> dependents(Path directory, String module, Path workDirectory) {
    try {
      Files.createDirectories(workDirectory);
      var listed = workDirectory.resolve("dependents.txt");
      Files.deleteIfExists(listed);
      var log = workDirectory.resolve("maven-dependents.log");
      var command = dependentsCommand(directory, module, listed);
      if (mvn(directory, command, log) != 0 || !Files.exists(listed)) {
        throw new SetupFailed(
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
  private static List<String> dependentsCommand(Path directory, String module, Path listed) {
    return List.of(
        mvn(directory),
        "-q",
        "-B",
        "validate",
        "-Dmaven.ext.class.path=" + extensionJar(),
        "-Dmutastic.dependents=" + listed,
        "-Dmutastic.module=" + (module.equals(":") ? "" : module.substring(1)));
  }

  private static List<String> command(Path directory, String module, Path out, String suite) {
    var command =
        new ArrayList<>(
            List.of(
                mvn(directory),
                "-q",
                "-B",
                "test-compile",
                "-Dmaven.ext.class.path=" + extensionJar(),
                "-Dmutastic.out=" + out,
                "-Dmutastic.suite=" + suite));
    if (!module.equals(":")) {
      command.addAll(List.of("-pl", module.substring(1), "-am"));
    }
    return command;
  }

  private static int mvn(Path directory, List<String> command, Path log)
      throws IOException, InterruptedException {
    try {
      return new ProcessBuilder(command)
          .directory(directory.toFile())
          .redirectErrorStream(true)
          .redirectOutput(log.toFile())
          .start()
          .waitFor();
    } catch (IOException e) {
      if (command.getFirst().equals("mvn")) {
        throw new SetupFailed(
            "%s has no Maven wrapper (mvnw), and there's no `mvn` to run instead"
                .formatted(directory));
      }
      throw e;
    }
  }

  /// The project's wrapper, as its own build runs; `mvn` on the PATH without one.
  private static String mvn(Path directory) {
    var wrapper = directory.resolve("mvnw");
    return Files.isExecutable(wrapper) ? wrapper.toString() : "mvn";
  }

  private static String noJava(Path directory, String module, String typed) {
    return module.equals(":")
        ? "%s has no Java code to mutate; name a module with some: mutastic run %s :<module>"
            .formatted(directory, typed)
        : "%s has no Java module %s: name a module by its directory, as -pl does (mutastic run %s :<directory>)"
            .formatted(directory, module, typed);
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
