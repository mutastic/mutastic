package io.mutastic.maven;

import io.mutastic.api.BuildTool;
import io.mutastic.api.BuildTool.SetupFailed;
import io.mutastic.api.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Maven builds, read by running `mvn test-compile` with mutastic's extension (see [MavenSetup]). A
/// module is named by its directory, as `-pl` takes it: `:services/api`.
public final class Maven implements BuildTool {
  @Override
  public String name() {
    return "Maven";
  }

  @Override
  public String buildFiles() {
    return "pom.xml";
  }

  @Override
  public boolean recognises(Path directory) {
    return Files.isRegularFile(directory.resolve("pom.xml"));
  }

  /// In the module's own `target`, so `mvn clean` clears it with everything else the build made.
  @Override
  public Path workDirectory(Path directory, String module) {
    return MavenSetup.moduleDirectory(directory, module).resolve("target/mutastic");
  }

  @Override
  public List<String> dependents(Path directory, String module, Path workDirectory) {
    return MavenSetup.dependents(directory, module, workDirectory);
  }

  @Override
  public Project read(Path directory, String module, Path workDirectory, String typed) {
    return read(new Request(directory, module, workDirectory, typed, DEFAULT_SUITE));
  }

  /// `suite` is `test`, Surefire's, or `integration-test` (or `integrationTest`), Failsafe's.
  @Override
  public Project read(Request request) {
    if (!MavenSetup.SUITES.contains(request.suite())) {
      throw new SetupFailed(
          "Maven builds have two test suites, test (Surefire) and integration-test (Failsafe), not "
              + request.suite());
    }
    return MavenSetup.read(request);
  }

  @Override
  public Project read(Path directory, String module, Path setup) {
    return MavenSetup.written(directory, module, setup);
  }
}
