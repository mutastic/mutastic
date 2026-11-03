package io.mutastic.gradle.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

/// How a run is set, lowest first: the plugin's defaults, the `mutastic { }` extension, a task's
/// own properties, then `-Pmutastic.<name>` on the command line, for one run.
class MutasticTaskTest {
  private final Project project = ProjectBuilder.builder().build();

  MutasticTaskTest() {
    project.getPluginManager().apply("java");
    project.getPluginManager().apply(MutasticPlugin.class);
  }

  @Test
  void thePluginsDefaults() {
    assertThat(task("mutasticRun").configured())
        .containsEntry("threshold", "80")
        .containsEntry("reports", "json,html")
        .containsEntry("suggestions", "true")
        .containsEntry("confirmCatches", "false")
        .containsEntry("testsFrom", "")
        .doesNotContainKeys("workers", "format", "since", "eachMutant");
  }

  @Test
  void theBuildFileOverTheDefaultsAndATaskOverTheBuildFile() {
    var extension = project.getExtensions().getByType(MutasticExtension.class);
    extension.getThreshold().set(60);
    extension.getTestsFrom().set(List.of(":app", ":web"));
    task("mutasticRun").getThreshold().set(70);

    assertThat(task("mutasticRun").configured())
        .containsEntry("threshold", "70")
        .containsEntry("testsFrom", ":app,:web");
    assertThat(task("mutasticVerify").configured()).containsEntry("threshold", "60");
  }

  /// A list on the command line replaces the build file's, rather than adding to it.
  @Test
  void theCommandLineOverBoth() {
    var extension = project.getExtensions().getByType(MutasticExtension.class);
    extension.getThreshold().set(60);
    extension.getTestsFrom().set(List.of(":app", ":web"));

    var settings =
        MutasticTask.settings(
            task("mutasticRun").configured(),
            Map.of("mutastic.threshold", "50", "mutastic.testsFrom", ":api"));

    assertThat(settings).containsEntry("threshold", "50").containsEntry("testsFrom", ":api");
  }

  private MutasticTask task(String name) {
    return (MutasticTask) project.getTasks().getByName(name);
  }
}
