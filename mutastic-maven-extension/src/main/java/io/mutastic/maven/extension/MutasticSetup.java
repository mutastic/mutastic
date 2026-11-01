package io.mutastic.maven.extension;

import java.nio.file.Path;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.ToolchainManager;
import org.eclipse.aether.RepositorySystem;
import org.jspecify.annotations.Nullable;

/// After the build, writes each Java module's setup (what Surefire's `test` would run it with) to
/// the directory `-Dmutastic.out` names: `setup.properties` for the module the build ran in,
/// `<path>/setup.properties` for one below it. With `-Dmutastic.dependents=<file>` and
/// `-Dmutastic.module=<path>`, writes instead the modules that depend on that one, from the
/// reactor's own graph. Does nothing without either.
@Named
@Singleton
public class MutasticSetup extends AbstractMavenLifecycleParticipant {
  private final LifecycleExecutor lifecycle;
  private final ToolchainManager toolchains;
  private final RepositorySystem repositories;

  @Inject
  public MutasticSetup(
      LifecycleExecutor lifecycle, ToolchainManager toolchains, RepositorySystem repositories) {
    this.lifecycle = lifecycle;
    this.toolchains = toolchains;
    this.repositories = repositories;
  }

  @Override
  public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
    var dependents = session.getUserProperties().getProperty("mutastic.dependents");
    if (dependents != null) {
      Dependents.write(session, Path.of(dependents));
    }
    var out = session.getUserProperties().getProperty("mutastic.out");
    if (out == null) {
      return;
    }
    var root = Path.of(session.getExecutionRootDirectory());
    for (MavenProject project : session.getProjects()) {
      if (!project.getPackaging().equals("pom")) {
        var file =
            Path.of(out)
                .resolve(root.relativize(project.getBasedir().toPath()))
                .resolve("setup.properties");
        write(session, project, file);
      }
    }
  }

  private void write(MavenSession session, MavenProject project, Path file)
      throws MavenExecutionException {
    var previous = session.getCurrentProject();
    session.setCurrentProject(project);
    try {
      var surefire = new Surefire(session, surefireTest(session));
      new ModuleSetup(project, surefire, new Launcher(session, project, repositories))
          .write(file, Java.of(surefire, session, toolchains));
    } catch (Exception e) {
      throw new MavenExecutionException("mutastic couldn't read " + project.getId(), e);
    } finally {
      session.setCurrentProject(previous);
    }
  }

  /// Surefire's `test` in the module's build plan, which evaluates its settings as the build would.
  private @Nullable MojoExecution surefireTest(MavenSession session) throws Exception {
    for (MojoExecution execution :
        lifecycle.calculateExecutionPlan(session, "test").getMojoExecutions()) {
      if (execution.getArtifactId().equals("maven-surefire-plugin")
          && execution.getGoal().equals("test")) {
        return execution;
      }
    }
    return null;
  }
}
