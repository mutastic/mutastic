package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.Rule;
import io.mutastic.report.Schemas;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationTest {
  @TempDir Path project;

  @Test
  void readsRulesPluginsAndFilters() throws Exception {
    write(
        """
        # the project's own say
        rules:
          - skip: com.acme.generated.**   # protobuf
            reason: protobuf output
          - mutate: "com.acme.Money#toString"
            reason: 'shown to customers'
        plugins:
          - build/libs/acme-filters.jar
        filters:
          - acme:no-metrics
        """);

    var configuration = Configuration.read(project);

    assertThat(configuration.rules())
        .containsExactly(
            new Rule(false, "com.acme.generated.**", "protobuf output"),
            new Rule(true, "com.acme.Money#toString", "shown to customers"));
    assertThat(configuration.plugins()).containsExactly(Path.of("build/libs/acme-filters.jar"));
    assertThat(configuration.filters()).containsExactly("acme:no-metrics");
  }

  @Test
  void noFileIsNoConfiguration() {
    assertThat(Configuration.read(project)).isEqualTo(Configuration.NONE);
  }

  /// Every skip says why, and a line it doesn't understand is named, never guessed at.
  @Test
  void aRuleNeedsAReasonAndAStrangeLineIsNamed() throws Exception {
    write("rules:\n  - skip: com.acme.**\nplugins:\n");
    assertThatThrownBy(() -> Configuration.read(project))
        .hasMessage(
            "mutastic.yaml line 3: the rule `skip: com.acme.**` needs a `reason:` under it");

    write("rule:\n  - skip: com.acme.**\n");
    assertThatThrownBy(() -> Configuration.read(project))
        .hasMessage(
            "mutastic.yaml line 1: expected extends:, rules:, plugins:, filters: or operators:");
  }

  /// The published schema (`/schemas/mutastic-yaml/v1.json`) takes what mutastic reads, and refuses
  /// what it refuses: an editor using it flags the mistakes a run would.
  @Test
  void theSchemaAgreesWithTheReader() throws Exception {
    var read =
        """
        rules:
          - skip: com.acme.generated.**
            reason: protobuf output
          - mutate: "com.acme.Money#toString"
            reason: 'shown to customers'
        plugins:
          - build/libs/acme-filters.jar
          - mutiny.yaml
        filters:
          - acme:no-metrics
        operators:
          - defaults
          - -stream/swap-match
        """;
    write(read);
    Configuration.read(project);
    assertThat(Schemas.yamlProblems("mutastic-yaml/v1.json", read)).isEmpty();

    for (String refused :
        java.util.List.of(
            "rules:\n  - skip: com.acme.**\n",
            "rule:\n  - skip: com.acme.**\n",
            "rules:\n  - skip: com.acme.**\n    mutate: com.acme.**\n    reason: both\n")) {
      write(refused);
      assertThatThrownBy(() -> Configuration.read(project))
          .as(refused)
          .isInstanceOf(Main.UsageException.class);
      assertThat(Schemas.yamlProblems("mutastic-yaml/v1.json", refused)).as(refused).isNotEmpty();
    }
  }

  /// The example of every key the Configuration guide shows, its last, is one mutastic reads and
  /// the schema takes. The sample's file it quotes before it is read in DocsOutputTest's runs.
  @Test
  void theGuidesExampleIsInTheSchema() throws Exception {
    var guide =
        Files.readString(
            Path.of(System.getProperty("mutastic.docs")).resolve("guides/configuration.mdx"));
    var example = guide.substring(guide.lastIndexOf("```yaml\nrules:") + "```yaml\n".length());
    example = example.substring(0, example.indexOf("```"));
    write(example);

    assertThat(Configuration.read(project).operators()).contains("-stream/swap-match");
    assertThat(Schemas.yamlProblems("mutastic-yaml/v1.json", example)).isEmpty();
  }

  /// A module's file adds the top's only when it says so: its own rules first, the top's plugins
  /// found from the module, and its own operators in place of the top's.
  @Test
  void aFileAddsWhatItExtends() throws Exception {
    write(
        """
        rules:
          - skip: com.acme.generated.**
            reason: protobuf output
        plugins:
          - tools/acme.jar
        filters:
          - acme:no-metrics
        operators:
          - stronger
        """);
    var module = Files.createDirectories(project.resolve("core"));
    var text =
        """
        extends: ../mutastic.yaml
        rules:
          - skip: com.acme.core.Legacy
            reason: on its way out
        filters:
          - acme:no-metrics
        """;
    Files.writeString(module.resolve("mutastic.yaml"), text);

    var configuration = Configuration.read(module);

    assertThat(configuration.rules())
        .containsExactly(
            new Rule(false, "com.acme.core.Legacy", "on its way out"),
            new Rule(false, "com.acme.generated.**", "protobuf output"));
    assertThat(configuration.plugins()).containsExactly(Path.of("../tools/acme.jar"));
    assertThat(configuration.filters()).containsExactly("acme:no-metrics");
    assertThat(configuration.operators()).containsExactly("stronger");
    assertThat(Schemas.yamlProblems("mutastic-yaml/v1.json", text)).isEmpty();

    Files.writeString(module.resolve("mutastic.yaml"), text + "operators:\n  - defaults\n");
    assertThat(Configuration.read(module).operators()).containsExactly("defaults");
  }

  /// A file that isn't there, or a loop, is named, never skipped.
  @Test
  void anExtendsThatCantBeFollowedIsNamed() throws Exception {
    var module = Files.createDirectories(project.resolve("core"));
    Files.writeString(module.resolve("mutastic.yaml"), "extends: ../top.yaml\n");
    assertThatThrownBy(() -> Configuration.read(module))
        .hasMessage("mutastic.yaml line 1: extends ../top.yaml, which isn't there");

    write("extends: core/mutastic.yaml\n");
    Files.writeString(module.resolve("mutastic.yaml"), "extends: ../mutastic.yaml\n");
    assertThatThrownBy(() -> Configuration.read(module))
        .hasMessage(
            "extends: goes round in a loop: mutastic.yaml extends ../mutastic.yaml extends"
                + " mutastic.yaml");
  }

  /// A module's rules are its nearest file's, whether the run is on the module or on the build's
  /// top: found by walking up from the module, but never out of its repository.
  @Test
  void aModuleUsesItsNearestFile() throws Exception {
    Files.createDirectories(project.resolve(".git"));
    var core = Files.createDirectories(project.resolve("services/core"));
    var api = Files.createDirectories(project.resolve("services/api"));
    write("rules:\n");
    Files.writeString(core.resolve("mutastic.yaml"), "rules:\n");

    assertThat(Configuration.nearest(core, project)).isEqualTo(core);
    assertThat(Configuration.nearest(core, core)).isEqualTo(core);
    assertThat(Configuration.nearest(api, project)).isEqualTo(project);
    assertThat(Configuration.nearest(api, api)).isEqualTo(project);
  }

  /// Outside a repository, no higher than the run's own directory: a stray file above the build is
  /// never read.
  @Test
  void outsideARepositoryTheRunsDirectoryIsTheTop() throws Exception {
    var build = Files.createDirectories(project.resolve("build-top"));
    var core = Files.createDirectories(build.resolve("core"));
    write("rules:\n");

    assertThat(Configuration.nearest(core, core)).isEqualTo(core);
    assertThat(Configuration.nearest(core, build)).isEqualTo(build);
  }

  private void write(String text) throws Exception {
    Files.writeString(project.resolve("mutastic.yaml"), text);
  }
}
