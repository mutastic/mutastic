package io.mutastic.maven.extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;

/// The reactor's modules with Java code, the ones mutastic can test: every one but those packaged
/// as `pom`. One module path per line, relative to the build's directory, which is `.`.
final class JavaModules {
  private JavaModules() {}

  static void write(MavenSession session, Path file) throws MavenExecutionException {
    var root = Path.of(session.getExecutionRootDirectory()).normalize();
    List<String> paths =
        session.getProjects().stream()
            .filter(project -> !project.getPackaging().equals("pom"))
            .map(project -> root.relativize(project.getBasedir().toPath().normalize()))
            .map(path -> path.toString().isEmpty() ? "." : path.toString())
            .map(path -> path.replace(java.io.File.separatorChar, '/'))
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
