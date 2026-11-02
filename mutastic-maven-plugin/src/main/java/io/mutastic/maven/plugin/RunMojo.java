package io.mutastic.maven.plugin;

import javax.inject.Inject;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.toolchain.ToolchainManager;
import org.eclipse.aether.RepositorySystem;

/// `mvn mutastic:run`: mutates the module's code and runs its tests against each mutant. Fails the
/// build when the score is below the threshold (`-Dmutastic.threshold`, 80 unless set).
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class RunMojo extends MutasticMojo {
  @Inject
  public RunMojo(
      LifecycleExecutor lifecycle, ToolchainManager toolchains, RepositorySystem repositories) {
    super(lifecycle, toolchains, repositories);
  }

  @Override
  String goal() {
    return "run";
  }

  @Override
  String failure() {
    return "the mutation score is below the threshold";
  }
}
