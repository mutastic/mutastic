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

  /// One module's setup, to `file`: for the extension after a build, and the Maven plugin during
  /// one.
  public void write(MavenSession session, MavenProject project, Path file)
      throws MavenExecutionException {
    write(
        session, project, file, session.getUserProperties().getProperty("mutastic.suite", "test"));
  }

  /// One module's setup for one test suite: `test`, as Surefire runs it, or `integration-test`, as
  /// Failsafe does.
  public void write(MavenSession session, MavenProject project, Path file, String suite)
      throws MavenExecutionException {
    var previous = session.getCurrentProject();
    session.setCurrentProject(project);
    try {
      boolean failsafe = suite.equals("integration-test") || suite.equals("integrationTest");
      var execution = testExecution(session, failsafe);
      if (failsafe && execution == null) {
        throw new MavenExecutionException(
            "mutastic: no test suite integration-test in %s; its build has no Failsafe"
                .formatted(project.getArtifactId()),
            project.getFile());
      }
      var settings = new Surefire(session, execution);
      new ModuleSetup(project, settings, new Launcher(session, project, repositories), failsafe)
          .write(file, Java.of(settings, session, toolchains));
    } catch (MavenExecutionException e) {
      throw e;
    } catch (Exception e) {
      throw new MavenExecutionException("mutastic couldn't read " + project.getId(), e);
    } finally {
      session.setCurrentProject(previous);
    }
  }

  /// Surefire's `test`, or Failsafe's `integration-test`, in the module's build plan, which
  /// evaluates its settings as the build would.
  private @Nullable MojoExecution testExecution(MavenSession session, boolean failsafe)
      throws Exception {
    var phase = failsafe ? "integration-test" : "test";
    var plugin = failsafe ? "maven-failsafe-plugin" : "maven-surefire-plugin";
    for (MojoExecution execution :
        lifecycle.calculateExecutionPlan(session, phase).getMojoExecutions()) {
      if (execution.getArtifactId().equals(plugin) && execution.getGoal().equals(phase)) {
        return execution;
      }
    }
    return null;
  }
}
