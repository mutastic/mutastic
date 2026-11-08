package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.engine.CallRule;
import io.mutastic.engine.CallRules;
import io.mutastic.report.Schemas;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The example plugins the docs show are ones mutastic reads: each rules file in
/// docs/src/data/plugins parses, with the operators the Plugins guide says it has.
class DocsPluginsTest {
  private static final Path PLUGINS =
      Path.of(System.getProperty("mutastic.docs")).resolve("../../data/plugins").normalize();

  @Test
  void theRulesFilesMutasticShipsAreOnesItReads() {
    assertThat(CallRules.read(PLUGINS.resolve("bigdecimal.yaml")))
        .extracting(CallRule::id)
        .containsExactly(
            "bigdecimal/swap-arithmetic", "bigdecimal/swap-scaling", "bigdecimal/remove-step");
    assertThat(CallRules.read(PLUGINS.resolve("reactive.yaml")))
        .extracting(CallRule::id)
        .containsExactly(
            "reactive/remove-stage",
            "reactive/remove-recovery",
            "reactive/swap-match",
            "reactive/swap-order");
  }

  /// The published schema (`/schemas/rules/v1.json`) takes every rules file mutastic ships or
  /// shows. Not the engine's own (`streams.yaml` and the rest): mutastic reads each value to the
  /// end of its line, so they leave an explanation with `: ` in it unquoted, which no YAML parser,
  /// and so no editor using the schema, reads.
  @Test
  void everyRulesFileIsInTheSchema() throws Exception {
    for (String example : List.of("bigdecimal.yaml", "reactive.yaml", "mutiny.yaml")) {
      assertThat(Schemas.yamlProblems("rules/v1.json", Files.readString(PLUGINS.resolve(example))))
          .as(example)
          .isEmpty();
    }
  }

  @Test
  void theSchemaRefusesWhatTheReaderRefuses(@TempDir Path work) throws Exception {
    var rule =
        """
        - id: acme/remove-step
          explanation: A step is removed.
          owners: com.acme.Pipeline
          methods: step
          rewrite: remove
          headline: no test needs `.step()` in {method}
          help: add a test the step changes
        """;
    var file = work.resolve("rule.yaml");
    Files.writeString(file, rule);
    assertThat(CallRules.read(file)).hasSize(1);
    assertThat(Schemas.yamlProblems("rules/v1.json", rule)).isEmpty();

    for (String refused :
        List.of(
            rule.replace("acme/remove-step", "Acme/RemoveStep"),
            rule.replace("rewrite: remove", "rewrite: delete"),
            rule.replace("  help: add a test the step changes\n", ""),
            rule + "  owner: com.acme.Other\n",
            rule + "  tier: strongest\n")) {
      Files.writeString(file, refused);
      assertThatThrownBy(() -> CallRules.read(file))
          .as(refused)
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(Schemas.yamlProblems("rules/v1.json", refused)).as(refused).isNotEmpty();
    }
  }

  @Test
  void theMutinyExampleIsARulesFileMutasticReads() {
    var rules = CallRules.read(PLUGINS.resolve("mutiny.yaml"));

    assertThat(rules)
        .extracting(CallRule::id)
        .containsExactly("mutiny/remove-selection", "mutiny/remove-recovery");
  }
}
