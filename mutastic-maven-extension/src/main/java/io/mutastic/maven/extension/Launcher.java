package io.mutastic.maven.extension;

import java.util.List;
import java.util.regex.Pattern;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;

/// Surefire brings its own JUnit Platform launcher, so a Maven project rarely declares one, and
/// mutastic's worker needs it: resolves the launcher of the project's platform engine's version.
final class Launcher {
  private static final Pattern ENGINE =
      Pattern.compile("junit-platform-engine-(?<version>[^/\\\\]+)\\.jar$");

  private final MavenSession session;
  private final MavenProject project;
  private final RepositorySystem repositories;

  Launcher(MavenSession session, MavenProject project, RepositorySystem repositories) {
    this.session = session;
    this.project = project;
    this.repositories = repositories;
  }

  /// Adds the launcher to `classpath` unless it's there, or there's no JUnit Platform to match.
  void addTo(List<String> classpath) throws Exception {
    String version = null;
    for (String element : classpath) {
      if (element.contains("junit-platform-launcher-")) {
        return;
      }
      var engine = ENGINE.matcher(element);
      if (engine.find()) {
        version = engine.group("version");
      }
    }
    if (version != null) {
      classpath.add(resolve(version));
    }
  }

  private String resolve(String version) throws Exception {
    var launcher =
        new DefaultArtifact("org.junit.platform", "junit-platform-launcher", "jar", version);
    var request = new ArtifactRequest(launcher, project.getRemoteProjectRepositories(), null);
    return repositories
        .resolveArtifact(session.getRepositorySession(), request)
        .getArtifact()
        .getFile()
        .getPath();
  }
}
