package io.mutastic.maven.plugin;

import javax.inject.Inject;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.toolchain.ToolchainManager;
import org.eclipse.aether.RepositorySystem;

/// `mvn mutastic:explain -Dmutastic.mutant=<id>`: everything known of one mutant, with its tests
/// run.
@Mojo(name = "explain", requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class ExplainMojo extends MutasticMojo {
  @Inject
  public ExplainMojo(
      LifecycleExecutor lifecycle, ToolchainManager toolchains, RepositorySystem repositories) {
    super(lifecycle, toolchains, repositories);
  }

  @Override
  String goal() {
    return "explain";
  }

  @Override
  String failure() {
    return "the mutant isn't caught";
  }
}
