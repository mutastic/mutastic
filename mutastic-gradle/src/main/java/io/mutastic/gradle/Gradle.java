package io.mutastic.gradle;

import io.mutastic.api.BuildTool;
import io.mutastic.api.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Gradle builds, read by running the build with mutastic's init script (see [GradleSetup]).
public final class Gradle implements BuildTool {
  @Override
  public String name() {
    return "Gradle";
  }

  @Override
  public String buildFiles() {
    return "settings.gradle(.kts) or build.gradle(.kts)";
  }

  @Override
  public boolean recognises(Path directory) {
    return GradleSetup.BUILD_FILES.stream()
        .anyMatch(name -> Files.isRegularFile(directory.resolve(name)));
  }

  /// `module` is a Gradle project path: `:data` keeps its own under `build/mutastic/data`.
  @Override
  public Path workDirectory(Path directory, String module) {
    var work = directory.resolve("build/mutastic");
    return module.equals(":") ? work : work.resolve(module.substring(1).replace(':', '/'));
  }

  @Override
  public List<String> dependents(Path directory, String module, Path workDirectory) {
    return GradleSetup.dependents(directory, module, workDirectory);
  }

  @Override
  public Project read(Path directory, String module, Path workDirectory, String typed) {
    return read(new Request(directory, module, workDirectory, typed, DEFAULT_SUITE));
  }

  /// The suite is a `Test` task: `test`, or `integrationTest` say.
  @Override
  public Project read(Request request) {
    return GradleSetup.read(request);
  }

  @Override
  public java.util.Optional<Building> building(Request request) {
    return java.util.Optional.of(GradleSetup.building(request));
  }

  @Override
  public Project read(Path directory, String module, Path setup) {
    return GradleSetup.written(directory, module, setup);
  }
}
