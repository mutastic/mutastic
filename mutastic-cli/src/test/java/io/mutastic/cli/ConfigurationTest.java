package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.Rule;
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
        .hasMessage("mutastic.yaml line 1: expected rules:, plugins: or filters:");
  }

  private void write(String text) throws Exception {
    Files.writeString(project.resolve("mutastic.yaml"), text);
  }
}
