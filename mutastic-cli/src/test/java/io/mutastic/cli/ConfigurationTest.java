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
        .hasMessage("mutastic.yaml line 1: expected rules:, plugins:, filters: or operators:");
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

  /// The example the Configuration guide shows is one mutastic reads and the schema takes.
  @Test
  void theGuidesExampleIsInTheSchema() throws Exception {
    var guide =
        Files.readString(
            Path.of(System.getProperty("mutastic.docs")).resolve("guides/configuration.mdx"));
    var example = guide.substring(guide.indexOf("```yaml\nrules:") + "```yaml\n".length());
    example = example.substring(0, example.indexOf("```"));
    write(example);

    assertThat(Configuration.read(project).operators()).contains("-stream/swap-match");
    assertThat(Schemas.yamlProblems("mutastic-yaml/v1.json", example)).isEmpty();
  }

  private void write(String text) throws Exception {
    Files.writeString(project.resolve("mutastic.yaml"), text);
  }
}
