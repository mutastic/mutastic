package io.mutastic.maven.plugin;

import javax.inject.Inject;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.toolchain.ToolchainManager;
import org.eclipse.aether.RepositorySystem;

/// `mvn mutastic:verify -Dmutastic.mutant=<id>`: runs one mutant's tests, and fails the build
/// unless they catch it: how a fix is proved.
@Mojo(name = "verify", requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class VerifyMojo extends MutasticMojo {
  @Inject
  public VerifyMojo(
      LifecycleExecutor lifecycle, ToolchainManager toolchains, RepositorySystem repositories) {
    super(lifecycle, toolchains, repositories);
  }

  @Override
  String goal() {
    return "verify";
  }

  @Override
  String failure() {
    return "the mutant isn't caught: its tests still pass with it in";
  }
}
