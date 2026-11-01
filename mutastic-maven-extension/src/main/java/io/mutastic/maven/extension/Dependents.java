package io.mutastic.maven.extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/// The Java modules downstream of one module in the reactor, directly or through others: the ones
/// whose tests can reach its code. One module path per line, relative to the build's directory.
final class Dependents {
  private Dependents() {}

  static void write(MavenSession session, Path file) throws MavenExecutionException {
    var root = Path.of(session.getExecutionRootDirectory());
    var module =
        root.resolve(session.getUserProperties().getProperty("mutastic.module", "")).normalize();
    var target =
        session.getProjects().stream()
            .filter(project -> project.getBasedir().toPath().normalize().equals(module))
            .findFirst();
    List<String> paths =
        target.isEmpty()
            ? List.of()
            : session.getProjectDependencyGraph().getDownstreamProjects(target.get(), true).stream()
                .filter(project -> !project.getPackaging().equals("pom"))
                .map(MavenProject::getBasedir)
                .map(basedir -> root.relativize(basedir.toPath().normalize()).toString())
                .sorted()
                .toList();
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.writeString(file, String.join("\n", paths));
    } catch (IOException e) {
      throw new MavenExecutionException("mutastic couldn't write " + file, e);
    }
  }
}
