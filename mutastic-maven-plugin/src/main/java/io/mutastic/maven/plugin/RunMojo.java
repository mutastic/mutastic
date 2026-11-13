package io.mutastic.maven.plugin;

import java.util.Map;
import javax.inject.Inject;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.toolchain.ToolchainManager;
import org.eclipse.aether.RepositorySystem;

/// `mvn mutastic:run`: mutates the module's code and runs its tests against each mutant. Fails the
/// build when the score is below the threshold (`-Dmutastic.threshold`, 80 unless set), or with
/// `-Dmutastic.failOnNew` when a mutant no test catches isn't in `.mutastic/baseline.yaml`.
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

  @Override
  String failure(Map<String, String> settings) {
    var failOnNew = settings.get("failOnNew");
    return failOnNew != null && !failOnNew.equals("false")
        ? "a mutant no test catches isn't in .mutastic/baseline.yaml"
        : failure();
  }
}
