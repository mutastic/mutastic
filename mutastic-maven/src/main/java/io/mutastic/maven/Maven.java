package io.mutastic.maven;

import io.mutastic.api.BuildTool;
import io.mutastic.api.Project;
import java.nio.file.Files;
import java.nio.file.Path;

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
  public Project read(Path directory, String module, Path workDirectory, String typed) {
    return MavenSetup.read(directory, module, workDirectory, typed);
  }
}
