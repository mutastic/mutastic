package io.mutastic.cli;

import static io.mutastic.cli.Main.Subcommand.EXPLAIN;
import static io.mutastic.cli.Main.Subcommand.IGNORE;
import static io.mutastic.cli.Main.Subcommand.NEXT;
import static io.mutastic.cli.Main.Subcommand.RUN;
import static io.mutastic.cli.Main.Subcommand.VERIFY;

import io.mutastic.cli.Option.Group;
import io.mutastic.cli.Option.Kind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Every option mutastic takes, in the order help lists them. The one place an option is declared:
/// the parser looks each up here, `--help` prints them from here, and the build plugins'
/// `mutastic.` properties are read back through here.
final class Options {
  private Options() {}

  private static final Set<Main.Subcommand> RUNS = EnumSet.of(RUN);

  /// The commands that run the module's mutants, every one or those on a change.
  private static final Set<Main.Subcommand> MODULE_RUNS = EnumSet.of(RUN, NEXT);

  private static final Set<Main.Subcommand> MUTANTS_RUN =
      EnumSet.of(RUN, VERIFY, EXPLAIN, NEXT, Main.Subcommand.BASELINE);

  /// The commands that show a mutant as a card: a run's, explain's and next's.
  private static final Set<Main.Subcommand> SHOWN = EnumSet.of(RUN, EXPLAIN, NEXT);

  private static final Set<Main.Subcommand> IGNORES = EnumSet.of(IGNORE);
  private static final Set<Main.Subcommand> GATES =
      EnumSet.of(Main.Subcommand.GATE_OPEN, Main.Subcommand.GATE_CHECK, Main.Subcommand.GATE_CLOSE);
  private static final Set<Main.Subcommand> EVERY = EnumSet.allOf(Main.Subcommand.class);
  private static final List<String> SWITCHES = List.of("auto", "always", "never");

  static final List<Option> ALL =
      List.of(
          new Option(
              "--threshold",
              Kind.VALUE,
              Group.RUNNING,
              "N",
              List.of(),
              "The score a run must reach, from 0 to 100; 80 unless set",
              RUNS,
              "threshold",
              true,
              "",
              "mutastic run --threshold=70"),
          new Option(
              "--fail-on-new",
              Kind.FLAG,
              Group.RUNNING,
              "",
              List.of(),
              "Pass when every mutant no test catches is in .mutastic/baseline.yaml, and fail on"
                  + " one that isn't, whatever the score: the threshold isn't applied. For a project"
                  + " whose score can't reach it yet; mutastic baseline writes the file",
              RUNS,
              "failOnNew",
              true,
              "",
              "mutastic run --fail-on-new"),
          new Option(
              "--since",
              Kind.OPTIONAL_VALUE,
              Group.RUNNING,
              "REF",
              List.of(),
              "Mutate only the lines changed since REF; the default branch without one",
              MODULE_RUNS,
              "since",
              true,
              "",
              "mutastic run --since=main"),
          new Option(
              "--only",
              Kind.VALUE,
              Group.RUNNING,
              "ID",
              List.of(),
              "Run one mutant, or several split by commas; the run passes when each is caught or skipped",
              RUNS,
              "only",
              false,
              "",
              "mutastic run --only=m855c8e3c"),
          new Option(
              "--tests-from",
              Kind.REPEATED,
              Group.RUNNING,
              "MODULE",
              List.of(),
              "Also run this module's tests over what this one's miss: :app, or dependents for"
                  + " every module that depends on it",
              MUTANTS_RUN,
              "testsFrom",
              true,
              "",
              "mutastic run . :core --tests-from=dependents"),
          new Option(
              "--workers",
              Kind.VALUE,
              Group.RUNNING,
              "N",
              List.of(),
              "How many workers run side by side; half the cores, from 1 to 4, unless set",
              MUTANTS_RUN,
              "workers",
              true,
              "",
              "mutastic run --workers=2"),
          new Option(
              "--confirm-catches",
              Kind.FLAG,
              Group.RUNNING,
              "",
              List.of(),
              "Run each catching test again on its own, to confirm the catch",
              MUTANTS_RUN,
              "confirmCatches",
              true,
              "",
              "mutastic run --confirm-catches"),
          new Option(
              "--kill-matrix",
              Kind.FLAG,
              Group.RUNNING,
              "",
              List.of(),
              "Run every test that reaches a caught mutant, to name each that catches it, not"
                  + " only the first: for measuring an order, slower",
              RUNS,
              "killMatrix",
              false,
              "",
              "mutastic run --kill-matrix --format=jsonl"),
          new Option(
              "--shard",
              Kind.VALUE,
              Group.RUNNING,
              "N/M",
              List.of(),
              "Run only the Nth of M shares of the mutants, to split one run across machines. It"
                  + " keeps its results in shares/N-of-M in the work directory for --from-shards",
              RUNS,
              "shard",
              false,
              "",
              "mutastic run --shard=2/4 --threshold=0"),
          new Option(
              "--from-shards",
              Kind.VALUE,
              Group.RUNNING,
              "DIR",
              List.of(),
              "Run no mutants: put together the results every share of a split run kept, found"
                  + " anywhere in DIR, as the whole run, with its reports, record and threshold",
              RUNS,
              "fromShards",
              false,
              "",
              "mutastic run --from-shards=shares --threshold=60"),
          new Option(
              "--no-suggestions",
              Kind.FLAG,
              Group.RUNNING,
              "",
              List.of(),
              "Don't look for a test to suggest for each missed mutant",
              SHOWN,
              "suggestions",
              true,
              "",
              "mutastic run --no-suggestions"),
          new Option(
              "--exclude-tests",
              Kind.REPEATED,
              Group.RUNNING,
              "CLASS",
              List.of(),
              "A test class to leave out of the run, by its name or full name: one that can't run"
                  + " reliably here, with a race or a fixed port; the summary names it",
              MUTANTS_RUN,
              "excludeTests",
              false,
              "",
              "mutastic run --exclude-tests=CheckoutRaceTest"),
          new Option(
              "--suite",
              Kind.VALUE,
              Group.RUNNING,
              "SUITE",
              List.of(),
              "Which of the build's test suites runs: a Gradle Test task by name, or Maven's"
                  + " integration-test (Failsafe); test unless set",
              MUTANTS_RUN,
              "suite",
              true,
              "",
              "mutastic run --suite=integrationTest"),
          new Option(
              "--operators",
              Kind.REPEATED,
              Group.RUNNING,
              "OPERATORS",
              List.of(),
              "Which operators run: a set (defaults, stronger, extended or all), an operator's id"
                  + " or namespace, or any with - in front to leave it out; defaults unless set",
              MODULE_RUNS,
              "operators",
              false,
              "",
              "mutastic run --operators=stronger"),
          new Option(
              "--experimental",
              Kind.REPEATED,
              Group.RUNNING,
              "FEATURE",
              List.of(Main.GHOSTS),
              "Turns on something experimental: ghosts, which insert the call most sites like a"
                  + " write make and it doesn't, and say whether any test notices; never in the score",
              MODULE_RUNS,
              "experimental",
              false,
              "MUTASTIC_EXPERIMENTAL",
              "mutastic run --experimental=ghosts"),
          new Option(
              "--report",
              Kind.REPEATED,
              Group.RUNNING,
              "KIND[:PATH]",
              List.of(),
              "A report file a full run writes: json, html, xml or sarif, or none; json and html"
                  + " unless set",
              RUNS,
              "reports",
              true,
              "",
              "mutastic run --report=xml"),
          new Option(
              "--format",
              Kind.VALUE,
              Group.OUTPUT,
              "FORMAT",
              Main.FORMATS,
              "How the result is shown; full on a terminal, concise elsewhere, agent for coding"
                  + " agents; explain takes full and jsonl",
              SHOWN,
              "format",
              true,
              "MUTASTIC_FORMAT, MUTASTIC_AGENT",
              "mutastic run --format=jsonl"),
          new Option(
              "--each-mutant",
              Kind.FLAG,
              Group.OUTPUT,
              "",
              List.of(),
              "A card for every missed mutant, not one for the mutants of each line",
              RUNS,
              "eachMutant",
              true,
              "",
              "mutastic run --each-mutant"),
          new Option(
              "--vocabulary",
              Kind.VALUE,
              Group.OUTPUT,
              "WORDS",
              Main.VOCABULARIES,
              "plain says caught and missed; classic says killed and survived",
              SHOWN,
              "vocabulary",
              false,
              "MUTASTIC_VOCABULARY",
              "mutastic run --vocabulary=classic"),
          new Option(
              "--color",
              Kind.VALUE,
              Group.OUTPUT,
              "WHEN",
              SWITCHES,
              "Colour: auto uses it on a terminal that wants it",
              SHOWN,
              "color",
              false,
              "NO_COLOR, FORCE_COLOR",
              "mutastic run --color=never"),
          new Option(
              "--hyperlinks",
              Kind.VALUE,
              Group.OUTPUT,
              "WHEN",
              SWITCHES,
              "File locations as links a terminal can open: auto uses them on a terminal",
              SHOWN,
              "hyperlinks",
              false,
              "",
              "mutastic run --hyperlinks=never"),
          new Option(
              "--progress",
              Kind.VALUE,
              Group.OUTPUT,
              "STYLE",
              List.of("auto", "bar", "counter", "none"),
              "Progress on stderr: a bar on a terminal, counter lines in logs, none for agents",
              MUTANTS_RUN,
              "progress",
              false,
              "",
              "mutastic run --progress=counter"),
          new Option(
              "--reason",
              Kind.VALUE,
              Group.IGNORING,
              "WHY",
              List.of(),
              "Why the mutant isn't worth a test: every ignore says",
              IGNORES,
              "reason",
              false,
              "",
              "mutastic ignore m855c8e3c --reason=\"only logs\""),
          new Option(
              "--as",
              Kind.VALUE,
              Group.IGNORING,
              "KIND",
              io.mutastic.api.Ignore.KINDS,
              "What sort of decision it is, counted apart in the summary",
              IGNORES,
              "as",
              false,
              "",
              "mutastic ignore m855c8e3c --as=unreachable --reason=\"totals are rounded to tens\""),
          new Option(
              "--remove",
              Kind.FLAG,
              Group.IGNORING,
              "",
              List.of(),
              "Take the mutant's ignore away, so it runs again",
              IGNORES,
              "remove",
              false,
              "",
              "mutastic ignore --remove m855c8e3c"),
          new Option(
              "--propose",
              Kind.FLAG,
              Group.IGNORING,
              "",
              List.of(),
              "Propose the ignore for a person to accept, rather than make it: a coding agent's way."
                  + " An equivalent or unreachable mutant's reason says the one case where it"
                  + " differs and why that can't happen",
              IGNORES,
              "propose",
              false,
              "",
              "mutastic ignore m855c8e3c --propose --as=unreachable --reason=\"Differs only when"
                  + " total is 100. That can't happen because prices end in 9.\""),
          new Option(
              "--accept",
              Kind.FLAG,
              Group.IGNORING,
              "",
              List.of(),
              "Accept a proposed ignore: it moves into the ignores, as proposed",
              IGNORES,
              "accept",
              false,
              "",
              "mutastic ignore --accept m855c8e3c"),
          new Option(
              "--prune",
              Kind.FLAG,
              Group.IGNORING,
              "",
              List.of(),
              "Take away every ignore whose code has changed; with baseline, every mutant the"
                  + " tests catch now",
              EnumSet.of(IGNORE, Main.Subcommand.BASELINE),
              "prune",
              false,
              "",
              "mutastic ignore --prune"),
          new Option(
              "--hook",
              Kind.VALUE,
              Group.GATE,
              "AGENT",
              List.of("claude"),
              "Run as the agent's hook: read its input on stdin, and answer as its hooks do",
              GATES,
              "",
              false,
              "",
              "mutastic gate check --hook=claude"),
          new Option(
              "--tries",
              Kind.VALUE,
              Group.GATE,
              "N",
              List.of(),
              "With --hook, how many times check sends the agent back before it lets it stop and"
                  + " says what's left; 3 unless set",
              EnumSet.of(Main.Subcommand.GATE_CHECK),
              "",
              false,
              "",
              "mutastic gate check --hook=claude --tries=5"),
          new Option(
              "--top",
              Kind.VALUE,
              Group.OUTPUT,
              "N",
              List.of(),
              "How many of the queue next shows, at most one a method and two a file; 1 unless"
                  + " set, at most "
                  + io.mutastic.report.Queue.MOST,
              EnumSet.of(Main.Subcommand.NEXT),
              "",
              false,
              "",
              "mutastic next --top=5"),
          new Option(
              "--setup",
              Kind.VALUE,
              Group.BUILD_PLUGINS,
              "FILE",
              List.of(),
              "The setup a build plugin wrote, instead of reading the build",
              EVERY,
              "",
              false,
              "",
              ""),
          new Option(
              "--commands",
              Kind.VALUE,
              Group.BUILD_PLUGINS,
              "FORM",
              List.of(),
              "How the commands the output offers are written: mutastic, maven or gradle",
              EVERY,
              "",
              false,
              "",
              ""));

  /// The option called `name`, `--threshold`.
  static Option named(String name) {
    return ALL.stream()
        .filter(option -> option.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new Main.UsageException("unknown option " + name));
  }

  /// The options help shows for `command`.
  static List<Option> shownFor(Main.Subcommand command) {
    return ALL.stream()
        .filter(option -> option.group() != Group.BUILD_PLUGINS)
        .filter(option -> option.commands().contains(command))
        .toList();
  }

  private static final String MAVEN_PROPERTY = "-Dmutastic.";
  private static final String GRADLE_PROPERTY = "-Pmutastic.";

  /// A build plugin's `mutastic.` property as the arguments it stands for, or empty for an argument
  /// that isn't one. `-Dmutastic.threshold=60` (or `-Pmutastic.`) → `--threshold=60`;
  /// `-Dmutastic.mutant=m855c8e3c` → the id, and `m855c8e3c,m2e491c40` → each, for `verify`. A flag
  /// is on when it's set without a value or to `true`, off for `false`: `-Pmutastic.prune`,
  /// `-Dmutastic.suggestions=false` → `--no-suggestions`. A list is joined by commas,
  /// `-Dmutastic.testsFrom=:app,:web`, and an empty `reports` writes none.
  static Optional<List<String>> fromProperty(String argument) {
    if (!argument.startsWith(MAVEN_PROPERTY) && !argument.startsWith(GRADLE_PROPERTY)) {
      return Optional.empty();
    }
    var assignment = argument.substring(MAVEN_PROPERTY.length());
    int equals = assignment.indexOf('=');
    var name = equals < 0 ? assignment : assignment.substring(0, equals);
    var value = equals < 0 ? "" : assignment.substring(equals + 1);
    if (name.equals("mutant")) {
      return Optional.of(List.of(value.split(",", -1)));
    }
    var option =
        ALL.stream()
            .filter(each -> each.property().equals(name))
            .findFirst()
            .orElseThrow(() -> new Main.UsageException("unknown property mutastic." + name));
    return Optional.of(arguments(option, value));
  }

  private static List<String> arguments(Option option, String value) {
    return switch (option.kind()) {
      case FLAG -> flag(option, value);
      case OPTIONAL_VALUE -> List.of(value.isEmpty() ? option.name() : option.name() + "=" + value);
      case VALUE -> List.of(option.name() + "=" + value);
      case REPEATED -> each(option, value);
    };
  }

  private static List<String> flag(Option option, String value) {
    if (!List.of("", "true", "false").contains(value)) {
      throw new Main.UsageException(
          "mutastic.%s is true or false, not %s".formatted(option.property(), value));
    }
    boolean on = !value.equals("false");
    return on != option.negated() ? List.of(option.name()) : List.of();
  }

  /// `--tests-from=` before each of `:app,:web`; an empty list of reports is none.
  private static List<String> each(Option option, String values) {
    var arguments = new ArrayList<String>();
    Arrays.stream(values.split(",", -1))
        .map(String::strip)
        .filter(value -> !value.isEmpty())
        .forEach(value -> arguments.add(option.name() + "=" + value));
    if (arguments.isEmpty() && option.property().equals("reports")) {
      arguments.add(option.name() + "=none");
    }
    return arguments;
  }
}
