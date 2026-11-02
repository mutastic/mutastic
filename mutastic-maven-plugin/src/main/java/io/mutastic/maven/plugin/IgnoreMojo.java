package io.mutastic.maven.plugin;

import javax.inject.Inject;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.toolchain.ToolchainManager;
import org.eclipse.aether.RepositorySystem;

/// `mvn mutastic:ignore -Dmutastic.mutant=<id> -Dmutastic.reason="<why>"`: records a decision not
/// to test one mutant in `.mutastic/ignores.yaml`, to commit and review.
@Mojo(name = "ignore", requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class IgnoreMojo extends MutasticMojo {
  @Inject
  public IgnoreMojo(
      LifecycleExecutor lifecycle, ToolchainManager toolchains, RepositorySystem repositories) {
    super(lifecycle, toolchains, repositories);
  }

  @Override
  String goal() {
    return "ignore";
  }

  @Override
  String failure() {
    return "mutastic couldn't record the ignore";
  }
}
