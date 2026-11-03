package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CommandTest {
  @Test
  void runDefaultsToTheCurrentDirectoryAndRootProject() {
    var command = Main.Command.parse(new String[] {"run"});

    assertThat(command)
        .isEqualTo(
            new Main.Command(
                Main.Subcommand.RUN,
                Path.of("."),
                ":",
                Optional.empty(),
                80,
                List.of(),
                false,
                Optional.empty(),
                "auto",
                "auto",
                "auto",
                true,
                Main.DEFAULT_REPORTS,
                Optional.empty(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                "test",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "mutastic"));
  }

  @Test
  void readsDirectoryModuleAndOptionsInAnyOrder() {
    var command =
        Main.Command.parse(
            new String[] {"run", ":api", "--format=jsonl", "../app", "--threshold=60"});

    assertThat(command)
        .isEqualTo(
            new Main.Command(
                Main.Subcommand.RUN,
                Path.of("../app"),
                ":api",
                Optional.of("jsonl"),
                60,
                List.of(),
                false,
                Optional.empty(),
                "auto",
                "auto",
                "auto",
                true,
                Main.DEFAULT_REPORTS,
                Optional.empty(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                "test",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "mutastic"));
  }

  /// What the build plugins pass: the setup they wrote, and their goals as the commands offered.
  @Test
  void readsWhatABuildPluginPasses() {
    var command =
        Main.Command.parse(
            new String[] {
              "run", "app", ":api", "--setup=target/setup.properties", "--commands=maven"
            });

    assertThat(command.setup()).contains(Path.of("target/setup.properties"));
    assertThat(command.commands()).isEqualTo("maven");
  }

  /// How the build plugins pass their settings: lists joined by commas, flags as true or false.
  @Test
  void readsTheBuildPluginsSettings() {
    var command =
        Main.Command.parse(
            new String[] {
              "run",
              "-Pmutastic.threshold=60",
              "-Dmutastic.testsFrom=:app,:web",
              "-Dmutastic.reports=json,xml:out/mutations.xml",
              "-Dmutastic.suggestions=false",
              "-Dmutastic.confirmCatches=false",
              "-Dmutastic.since="
            });

    assertThat(command.threshold()).isEqualTo(60);
    assertThat(command.testsFrom()).containsExactly(":app", ":web");
    assertThat(command.reports())
        .containsExactly(
            new Main.RequestedReport("json", Optional.empty()),
            new Main.RequestedReport("xml", Optional.of(Path.of("out/mutations.xml"))));
    assertThat(command.suggestions()).isFalse();
    assertThat(command.confirmCatches()).isFalse();
    assertThat(command.since()).contains("");
    assertThat(Main.Command.parse(new String[] {"run", "-Dmutastic.reports="}).reports()).isEmpty();
  }

  @Test
  void verifyTakesTheMutantIdFirst() {
    var command = Main.Command.parse(new String[] {"verify", "m855c8e3c", "samples/pricing"});

    assertThat(command.verify()).isTrue();
    assertThat(command.only()).contains("m855c8e3c");
    assertThat(command.directory()).isEqualTo(Path.of("samples/pricing"));
  }

  /// An option that always takes a value takes it after `=` or as the next word.
  @Test
  void aValueFollowsItsOptionAfterASpaceToo() {
    var command =
        parse("run", "--only", "m855c8e3c", "--threshold", "60", "--tests-from", ":app", "../app");

    assertThat(command.only()).contains("m855c8e3c");
    assertThat(command.threshold()).isEqualTo(60);
    assertThat(command.testsFrom()).containsExactly(":app");
    assertThat(command.directory()).isEqualTo(Path.of("../app"));
    assertThatThrownBy(() -> parse("run", "--only")).hasMessage("--only needs a value: --only=ID");
  }

  /// `--since` may go without a value, so the word after it is the directory: one that isn't a
  /// directory was meant as its value, and the error says how to write it.
  @Test
  void sinceTakesItsValueOnlyAfterEquals() {
    var command = parse("run", "--since", ".");

    assertThat(command.since()).contains("");
    assertThat(command.directory()).isEqualTo(Path.of("."));
    assertThatThrownBy(() -> parse("run", "--since", "main"))
        .hasMessage("there's no directory main: for the changes since main, write --since=main");
  }

  @Test
  void aScoreIsFrom0To100AndAFlagTakesNoValue() {
    assertThatThrownBy(() -> parse("run", "--threshold=101"))
        .hasMessage("--threshold is from 0 to 100, not 101");
    assertThatThrownBy(() -> parse("run", "--threshold=-1"))
        .hasMessage("--threshold is from 0 to 100, not -1");
    assertThatThrownBy(() -> parse("ignore", "--prune=yes")).hasMessage("--prune takes no value");
  }

  /// Gradle sets a property given without a value to the empty string: a flag is then on.
  @Test
  void aFlagPropertyWithNoValueIsOn() {
    assertThat(parse("ignore", "-Pmutastic.prune=").ignore().orElseThrow().prune()).isTrue();
    assertThat(parse("ignore", "-Dmutastic.prune").ignore().orElseThrow().prune()).isTrue();
    assertThat(parse("run", "-Pmutastic.suggestions=true").suggestions()).isTrue();
    assertThatThrownBy(() -> parse("run", "-Dmutastic.confirmCatches=maybe"))
        .hasMessage("mutastic.confirmCatches is true or false, not maybe");
    assertThatThrownBy(() -> parse("run", "-Dmutastic.fast=true"))
        .hasMessage("unknown property mutastic.fast");
  }

  private static Main.Command parse(String... args) {
    return Main.Command.parse(args);
  }

  @Test
  void rejectsWhatItDoesntKnow() {
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--fast"}))
        .hasMessage("unknown option --fast");
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--format=xml"}))
        .hasMessage("unknown format xml (one of full, brief, concise, grouped, agent, jsonl)");
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"verify"}))
        .hasMessage("verify needs a mutant id");
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--workers=0"}))
        .hasMessage("--workers must be at least 1");
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--threshold=high"}))
        .hasMessage("--threshold needs a whole number, not high");
  }

  @Test
  void reportsReplaceTheDefaultsAndTakeAPath() {
    assertThat(
            Main.Command.parse(new String[] {"run", "--report=xml", "--report=json:out/m.json"})
                .reports())
        .containsExactly(
            new Main.RequestedReport("xml", Optional.empty()),
            new Main.RequestedReport("json", Optional.of(Path.of("out/m.json"))));
    assertThat(Main.Command.parse(new String[] {"run", "--report=none"}).reports()).isEmpty();
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--report=pdf"}))
        .hasMessage("unknown report pdf (one of json, html, xml, none)");
  }

  @Test
  void readsOtherModulesWhoseTestsRun() {
    var command =
        Main.Command.parse(new String[] {"run", ":core", "--tests-from=:app", "--tests-from=:web"});

    assertThat(command.testsFrom()).containsExactly(":app", ":web");
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--tests-from=app"}))
        .hasMessageContaining("--tests-from takes a module, as :app");
  }

  @Test
  void readsTheWorkerCount() {
    assertThat(Main.Command.parse(new String[] {"run", "--workers=3"}).workers()).contains(3);
  }
}
