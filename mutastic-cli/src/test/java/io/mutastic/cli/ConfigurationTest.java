package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.mutastic.api.Rule;
import io.mutastic.report.LeftOut;
import io.mutastic.report.Schemas;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
        .hasMessage("mutastic.yaml line 1: rule: isn't a section. There's one called rules:.");

    write("garbage\n");
    assertThatThrownBy(() -> Configuration.read(project))
        .hasMessage(
            "mutastic.yaml line 1: garbage isn't a section; the sections are extends:, rules:,"
                + " plugins:, filters:, operators:, exclude-tests: and gate:");
  }

  /// A list on its section's line is read by YAML but not by mutastic, which says how to write it.
  @Test
  void aListOnItsSectionsLineIsShownAsOneItemALine() throws Exception {
    write("operators: [stronger]\n");

    assertThatThrownBy(() -> Configuration.read(project))
        .hasMessage(
            "mutastic.yaml line 1: operators: takes a list, an item a line under it:%n"
                + "  operators:%n"
                + "    - stronger",
            "");
  }

  /// `gate:` chooses the gate's operators apart from everyday runs', each named by its line, and
  /// takes nothing else.
  @Test
  void theGateChoosesItsOwnOperators() throws Exception {
    write("operators:\n  - defaults\ngate:\n  operators:\n    - stronger\n    - -stream\n");
    var configuration = Configuration.read(project);

    assertThat(configuration.operators()).containsExactly("defaults");
    assertThat(configuration.gateOperators()).containsExactly("stronger", "-stream");
    assertThat(configuration.place(Configuration.GATE_OPERATORS, "-stream"))
        .isEqualTo("mutastic.yaml line 6");
    for (var refused :
        java.util.List.of(
            "gate: {operators: stronger}\n", "gate:\n  - stronger\n", "gate:\n  threshold:\n")) {
      write(refused);
      assertThatThrownBy(() -> Configuration.read(project))
          .as(refused)
          .hasMessageContaining("gate: takes operators:, a list under it");
    }
  }

  /// A rule with a close verb, a filter no plugin has and a plugin that isn't there are each named
  /// with their line.
  @Test
  void eachItemsErrorNamesItsLine() throws Exception {
    write("rules:\n  - skp: com.acme.**\n    reason: generated\n");
    assertThatThrownBy(() -> Configuration.read(project))
        .hasMessage("mutastic.yaml line 2: skp: isn't a rule. There's one called skip:.");

    write("# filters\nfilters:\n  - acme:no-metrics\n");
    var filters = Configuration.read(project);
    assertThatThrownBy(() -> filters.policy(project))
        .isInstanceOf(Main.ConfigException.class)
        .hasMessage(
            "mutastic.yaml line 3: no plugin has the filter acme:no-metrics; the plugins have"
                + " none");

    write("plugins:\n  - build/libs/acme.jar\n");
    var plugins = Configuration.read(project);
    assertThatThrownBy(() -> plugins.policy(project))
        .hasMessage("mutastic.yaml line 2: there's no plugin build/libs/acme.jar, from " + project);
    assertThat(plugins.place("operators:", "stronger")).isEqualTo(Configuration.FILE);
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
          - mutate: com.acme.Worker#run
            arid: [mutastic:waits, mutastic:output]
            reason: callers wait for it
        plugins:
          - build/libs/acme-filters.jar
          - mutiny.yaml
        filters:
          - acme:no-metrics
        operators:
          - defaults
          - -stream/swap-match
        gate:
          operators:
            - stronger
        """;
    write(read);
    Configuration.read(project);
    assertThat(Schemas.yamlProblems("mutastic-yaml/v1.json", read)).isEmpty();

    for (String refused :
        java.util.List.of(
            "rules:\n  - skip: com.acme.**\n",
            "rule:\n  - skip: com.acme.**\n",
            "rules:\n  - skip: com.acme.**\n    mutate: com.acme.**\n    reason: both\n",
            "gate:\n  threshold: 90\n",
            "rules:\n  - skip: com.acme.**\n    arid: [mutastic:waits]\n    reason: r\n",
            "rules:\n  - mutate: com.acme.**\n    arid: [mutastic:wait]\n    reason: r\n",
            "rules:\n  - mutate: com.acme.**\n    arid: []\n    reason: r\n")) {
      write(refused);
      assertThatThrownBy(() -> Configuration.read(project))
          .as(refused)
          .isInstanceOf(Main.ConfigException.class);
      assertThat(Schemas.yamlProblems("mutastic-yaml/v1.json", refused)).as(refused).isNotEmpty();
    }
  }

  /// A `mutate:` rule's `arid:` names the arid rules it brings back, on its line or under it,
  /// before or after its reason; without one it brings back every arid skip.
  @Test
  void aMutateRuleNamesTheAridRulesItBringsBack() throws Exception {
    write(
        """
        rules:
          - mutate: com.acme.Worker#run
            arid: [mutastic:waits, "mutastic:output"]
            reason: callers wait for it
          - mutate: com.acme.Pool#**
            reason: sized by contract
            arid: mutastic:capacity
          - mutate: com.acme.Clock#**
            arid:
              - mutastic:wait-times
              - mutastic:waits
            reason: timing is the product
          - mutate: com.acme.Money#toString
            reason: shown to customers
        """);

    assertThat(Configuration.read(project).rules())
        .extracting(Rule::target, Rule::arid)
        .containsExactly(
            tuple("com.acme.Worker#run", java.util.List.of("mutastic:waits", "mutastic:output")),
            tuple("com.acme.Pool#**", java.util.List.of("mutastic:capacity")),
            tuple("com.acme.Clock#**", java.util.List.of("mutastic:wait-times", "mutastic:waits")),
            tuple("com.acme.Money#toString", java.util.List.of()));
  }

  @Test
  void anAridListNamesMutasticsRulesOnAMutateRule() throws Exception {
    write("rules:\n  - mutate: com.acme.**\n    arid: [mutastic:wait]\n    reason: r\n");
    assertThatThrownBy(() -> Configuration.read(project))
        .hasMessage(
            "mutastic.yaml line 3: mutastic:wait isn't an arid rule. There's one called"
                + " mutastic:waits.");

    write("rules:\n  - mutate: com.acme.**\n    arid: [sleeps]\n    reason: r\n");
    assertThatThrownBy(() -> Configuration.read(project))
        .hasMessageStartingWith("mutastic.yaml line 3: sleeps isn't an arid rule; the rules are")
        .hasMessageContaining("mutastic:waits");

    write("rules:\n  - skip: com.acme.**\n    arid: [mutastic:waits]\n    reason: r\n");
    assertThatThrownBy(() -> Configuration.read(project))
        .hasMessageStartingWith("mutastic.yaml line 3: `arid:` goes on a `mutate:` rule");

    write("rules:\n  - mutate: com.acme.**\n    arid: []\n    reason: r\n");
    assertThatThrownBy(() -> Configuration.read(project))
        .hasMessageContaining("has an empty `arid:` list");
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

  /// `exclude-tests:` leaves tests out of every run, each with its reason: a test left out without
  /// one may be a broken test hidden. A module's file adds its parent's, each test once, with the
  /// module's reason. The Rust edition's `config.rs` has the same cases.
  @Test
  void aTestLeftOutSaysWhy() throws Exception {
    var text =
        """
        exclude-tests:
          - test: CheckoutRaceTest
            reason: shares port 8080 with the payment stub
          - test: "com.acme.PricingTest#bigOrders"
            reason: rounding changes next week
        """;
    write(text);
    assertThat(Configuration.read(project).excludedTests())
        .extracting(LeftOut::test, LeftOut::reason)
        .containsExactly(
            tuple("CheckoutRaceTest", Optional.of("shares port 8080 with the payment stub")),
            tuple("com.acme.PricingTest#bigOrders", Optional.of("rounding changes next week")));
    assertThat(Schemas.yamlProblems("mutastic-yaml/v1.json", text)).isEmpty();

    var module = Files.createDirectories(project.resolve("core"));
    Files.writeString(
        module.resolve("mutastic.yaml"),
        """
        extends: ../mutastic.yaml
        exclude-tests:
          - test: CheckoutRaceTest
            reason: the module's own
        """);
    assertThat(Configuration.read(module).excludedTests())
        .extracting(LeftOut::test, LeftOut::reason)
        .containsExactly(
            tuple("CheckoutRaceTest", Optional.of("the module's own")),
            tuple("com.acme.PricingTest#bigOrders", Optional.of("rounding changes next week")));
  }

  @Test
  void aTestLeftOutWithoutAReasonIsRefused() throws Exception {
    var refused =
        java.util.Map.of(
            "exclude-tests:\n  - test: RacyTest\n",
            "mutastic.yaml line 2: the test `RacyTest` needs a `reason:` under it: why it's left"
                + " out, which the summary shows",
            "exclude-tests:\n  - test: a\n    reason: ''\noperators:\n  - stronger\n",
            "mutastic.yaml line 4: the test `a` needs a `reason:` under it: why it's left out,"
                + " which the summary shows",
            "exclude-tests:\n  - test: a\n  - test: b\n    reason: r\n",
            "mutastic.yaml line 3: the test `a` needs a `reason:` under it: why it's left out,"
                + " which the summary shows",
            "exclude-tests:\n  - test: a\n    reason: r\n    reason: s\n",
            "mutastic.yaml line 4: the test `a` has one `reason:`",
            "exclude-tests:\n  - RacyTest\n",
            "mutastic.yaml line 2: a test to leave out is `- test: <name>`, then `reason: …`",
            "exclude-tests:\n  reason: r\n",
            "mutastic.yaml line 2: a test to leave out is `- test: <name>`, then `reason: …`");
    for (var each : refused.entrySet()) {
      write(each.getKey());
      assertThatThrownBy(() -> Configuration.read(project))
          .as(each.getKey())
          .hasMessage(each.getValue());
    }
    assertThat(Schemas.yamlProblems("mutastic-yaml/v1.json", "exclude-tests:\n  - test: a\n"))
        .isNotEmpty();
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

  /// The module's directory as the build tool reports it and the run's as the person typed it may
  /// name one directory two ways, as `/private/var/…` and `/var/…` do on macOS: the module's file
  /// is still found. Once it wasn't, and a module's rules were dropped in a temp directory.
  @Test
  void aModuleIsFoundThroughALink() throws Exception {
    var build = Files.createDirectories(project.resolve("private/build-top"));
    var core = Files.createDirectories(build.resolve("core"));
    Files.writeString(core.resolve("mutastic.yaml"), "rules:\n");
    var typed = Files.createSymbolicLink(project.resolve("build-top"), build);

    assertThat(Configuration.nearest(core, typed)).isEqualTo(core);
    assertThat(Configuration.nearest(typed.resolve("core"), build))
        .isEqualTo(typed.resolve("core"));
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
