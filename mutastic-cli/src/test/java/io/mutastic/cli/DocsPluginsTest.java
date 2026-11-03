package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.CallRule;
import io.mutastic.engine.CallRules;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/// The example plugins the docs show are ones mutastic reads: each rules file in
/// docs/src/data/plugins parses, with the operators the Plugins guide says it has.
class DocsPluginsTest {
  private static final Path PLUGINS =
      Path.of(System.getProperty("mutastic.docs")).resolve("../../data/plugins").normalize();

  @Test
  void theMutinyExampleIsARulesFileMutasticReads() {
    var rules = CallRules.read(PLUGINS.resolve("mutiny.yaml"));

    assertThat(rules)
        .extracting(CallRule::id)
        .containsExactly("mutiny/remove-selection", "mutiny/remove-recovery");
  }
}
