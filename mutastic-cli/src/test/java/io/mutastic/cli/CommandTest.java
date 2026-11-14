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
                new Main.Shown(
                    Optional.empty(), "auto", "auto", Optional.empty(), Optional.empty(), false),
                new Main.Bar(80, false),
                List.of(),
                new Main.Catches(false, false),
                Optional.empty(),
                new Main.Extras(true, false),
                Main.DEFAULT_REPORTS,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                "test",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "mutastic",
                new Main.Gating("", 3),
                java.util.Set.of(),
                1,
                Main.Sharing.NONE));
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
                new Main.Shown(
                    Optional.of("jsonl"),
                    "auto",
                    "auto",
                    Optional.empty(),
                    Optional.empty(),
                    false),
                new Main.Bar(60, false),
                List.of(),
                new Main.Catches(false, false),
                Optional.empty(),
                new Main.Extras(true, false),
                Main.DEFAULT_REPORTS,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                "test",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "mutastic",
                new Main.Gating("", 3),
                java.util.Set.of(),
                1,
                Main.Sharing.NONE));
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

  /// `--fail-on-new` is run's, as the flag or a build plugin's property: a build file that sets it
  /// for every task passes it to `verify` too, which takes no notice. A run of mutants asked for by
  /// id passes on each one's fate, so it isn't judged on new misses.
  @Test
  void failOnNewIsRunsAlone() {
    assertThat(parse("run", "--fail-on-new").failOnNew()).isTrue();
    assertThat(parse("run", "-Pmutastic.failOnNew=true").failOnNew()).isTrue();
    assertThat(parse("run", "-Dmutastic.failOnNew=false").failOnNew()).isFalse();
    assertThat(parse("run").failOnNew()).isFalse();
    assertThat(parse("verify", "m855c8e3c", "-Dmutastic.failOnNew=true").failOnNew()).isFalse();
    assertThatThrownBy(() -> parse("run", "--fail-on-new", "--only=m855c8e3c"))
        .hasMessageStartingWith("--fail-on-new judges a run of the module on its new misses");
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

  /// An empty id is said to be one, not looked for as a mutant with no name.
  @Test
  void anEmptyOnlyIdIsRefused() {
    assertThatThrownBy(() -> parse("run", "--only="))
        .hasMessage("--only needs a mutant's id: --only=ID, as a card shows it");
    assertThatThrownBy(() -> parse("run", "--only=m855c8e3c,,m2e491c40"))
        .hasMessage("--only=m855c8e3c,,m2e491c40 has an empty id between its commas: --only=ID,ID");
  }

  /// A proposal says why in a form a person can check: an equivalent or unreachable mutant's reason
  /// has a because, and no proposal keeps the template's parts unfilled.
  @Test
  void aProposalThatAMutantCantMatterSaysWhy() {
    var argued =
        "--reason=Differs only when total is 100. That can't happen because prices end in 9.";

    assertThat(parse("ignore", "m855c8e3c", "--propose", "--as=equivalent", argued).ignore())
        .isPresent();
    assertThatThrownBy(
            () -> parse("ignore", "m855c8e3c", "--propose", "--as=unreachable", "--reason=never"))
        .hasMessageStartingWith(
            "a proposal that a mutant is unreachable needs a reason a person can check")
        .hasMessageEndingWith(
            "--reason=\"Differs only when <case>. That can't happen because <why>.\"");
    assertThatThrownBy(
            () ->
                parse(
                    "ignore",
                    "m855c8e3c",
                    "--propose",
                    "--as=telemetry",
                    "--reason=Differs only when <case>. That can't happen because <why>."))
        .hasMessageStartingWith("the reason still has the template's <case> or <why>");
    assertThat(
            parse("ignore", "m855c8e3c", "--propose", "--as=telemetry", "--reason=logs").ignore())
        .as("other kinds keep a free reason")
        .isPresent();
    assertThat(parse("ignore", "m855c8e3c", "--as=equivalent", "--reason=same").ignore())
        .as("a person's own ignore isn't argued with")
        .isPresent();
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

  /// `--shard=2/4`: the second of four shares, each from 1 to how many there are.
  @Test
  void aShareIsOneOfHowMany() {
    assertThat(parse("run", "--shard=2/4").shard()).contains(new io.mutastic.engine.Shard(2, 4));
    assertThatThrownBy(() -> parse("run", "--shard=5/4"))
        .hasMessage("--shard is from 1 to 4, not 5");
    assertThatThrownBy(() -> parse("run", "--shard=0/4"))
        .hasMessage("--shard is from 1 to 4, not 0");
    assertThatThrownBy(() -> parse("run", "--shard=2"))
        .hasMessage("--shard needs a share and how many, as 2/4, not 2");
  }

  /// `--from-shards`: a run made of the shares' results runs no mutant, so what's for running them
  /// is refused, as is a share made of shares.
  @Test
  void aRunMadeOfSharesRunsNothing() {
    assertThat(parse("run", "--from-shards=shares").fromShards()).contains(Path.of("shares"));
    assertThatThrownBy(() -> parse("run", "--from-shards=shares", "--shard=1/2"))
        .hasMessageEndingWith("not with --shard, which the shares run with");
    assertThatThrownBy(() -> parse("run", "--from-shards=shares", "--since=main"))
        .hasMessageEndingWith("not with --since, which the shares run with");
    assertThatThrownBy(() -> parse("run", "--from-shards=shares", "--tests-from=:app"))
        .hasMessageEndingWith("not with --tests-from, which the shares run with");
    assertThatThrownBy(() -> parse("verify", "m855c8e3c", "--from-shards=shares"))
        .hasMessage("--from-shards is run's: mutastic run --from-shards=DIR");
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
        .hasMessage("there's no property mutastic.fast.");
    assertThatThrownBy(() -> parse("run", "-Dmutastic.treshold=70"))
        .hasMessage(
            "there's no property mutastic.treshold. There's one called mutastic.threshold.");
  }

  private static Main.Command parse(String... args) {
    return Main.Command.parse(args);
  }

  @Test
  void rejectsWhatItDoesntKnow() {
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--fast"}))
        .hasMessage("run has no option --fast");
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--format=xml"}))
        .hasMessage("--format takes full, brief, concise, grouped, agent or jsonl, not xml");
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"verify"}))
        .hasMessageStartingWith("verify needs a mutant id first")
        .hasMessageEndingWith("mutastic verify m855c8e3c <directory>");
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--workers=0"}))
        .hasMessage("--workers must be at least 1");
    assertThatThrownBy(() -> Main.Command.parse(new String[] {"run", "--threshold=high"}))
        .hasMessage("--threshold needs a whole number, not high");
  }

  /// What's in the id's place but isn't one is named, with the command it needs, rather than taken
  /// as the id and the current directory as the project.
  @Test
  void theIdComesFirst() {
    assertThatThrownBy(() -> parse("explain", "src"))
        .hasMessage(
            "src is a directory, not a mutant id. explain needs a mutant id first, an m and eight"
                + " hex digits as a run prints them:%n  mutastic explain m855c8e3c src",
            "");
    assertThatThrownBy(() -> parse("verify", "m855c8e3c,price"))
        .hasMessageStartingWith("m855c8e3c,price isn't a mutant id. verify needs")
        .hasMessageEndingWith("mutastic verify m855c8e3c <directory>");
    assertThatThrownBy(() -> parse("ignore", ":core", "--reason=x"))
        .hasMessageStartingWith(":core is a module, not a mutant id. ignore needs")
        .hasMessageEndingWith("mutastic ignore m855c8e3c <directory> :core");
    assertThat(parse("verify", "m855c8e3c,m0d5b4a21", "src").only())
        .containsExactly("m855c8e3c", "m0d5b4a21");
  }

  /// A word a command doesn't take is refused, not dropped: `run` with `explain m…` after it would
  /// otherwise run, and look like the explain.
  @Test
  void wordsACommandDoesntTakeAreRefused() {
    assertThatThrownBy(() -> parse("run", "samples/pricing", "explain", "md3a59003"))
        .hasMessage("run doesn't take explain md3a59003: mutastic run [DIR] [:MODULE] [OPTIONS]");
    assertThatThrownBy(() -> parse("explain", "m855c8e3c", "src", "m0d5b4a21"))
        .hasMessage("explain doesn't take m0d5b4a21: mutastic explain ID [DIR] [:MODULE]");
    assertThatThrownBy(() -> parse("gate", "check", ".", ":core", ":app"))
        .hasMessage("gate check doesn't take :app: mutastic gate check [DIR] [:MODULE]");
    assertThat(parse("verify", "m855c8e3c", "m0d5b4a21", "src", ":core").module())
        .isEqualTo(":core");
    assertThat(parse("run", ":core", "src").directory()).isEqualTo(Path.of("src"));
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
        .hasMessage("--report takes json, html, xml, sarif or none, not pdf");
    assertThat(Main.Command.parse(new String[] {"run", "--report=sarif"}).reports())
        .containsExactly(new Main.RequestedReport("sarif", Optional.empty()));
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
