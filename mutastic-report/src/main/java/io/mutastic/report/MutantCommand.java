package io.mutastic.report;

import java.util.List;
import java.util.stream.Collectors;

/// The commands every output offers for one mutant, in the order shown. Cards, agent text, the
/// event stream and the summary's `Next:` line all take them from here, so a command added here
/// appears everywhere, and the CLI's tests check that each one is a command the CLI has.
///
/// Each is written by the run's [Invocation]: for the project the run was on (its directory and
/// module, as typed from where mutastic ran), and as the `mutastic` command or a Maven goal, as it
/// was started. So it works as it's copied: an id means nothing in another project.
public enum MutantCommand {
  /// How to prove a fix: the mutant is caught.
  DONE_WHEN("done when", "doneWhen", "verify %s", " passes", Shown.EVERYWHERE),
  /// How to see it again on its own.
  REPRODUCE("reproduce", "reproduce", "run --only=%s", "", Shown.EVERYWHERE),
  /// How to decide it isn't worth a test: recorded, with the reason, for review.
  IGNORE("or ignore", "ignore", "ignore %s --reason=\"<why>\"", "", Shown.WITH_A_MUTANT),
  /// How a coding agent says it isn't worth a test: a proposal, for a person to accept. Offered to
  /// agents instead of [#IGNORE]: whether a case can happen is a person's decision.
  PROPOSE("or propose", "propose", "ignore %s --propose --reason=\"<why>\"", "", Shown.TO_AGENTS),
  /// Everything known of it, for a line that only names it.
  EXPLAIN("explain", "explain", "explain %s", "", Shown.IN_THE_SUMMARY);

  /// Where a command is offered: with each mutant (cards, agent text, events), in the summary's
  /// `Next:` line, or both. A full card explains its mutant, so `explain` is offered for the
  /// one-line formats, by the summary; ignoring isn't a way to a fix, so not there.
  enum Shown {
    EVERYWHERE,
    WITH_A_MUTANT,
    IN_THE_SUMMARY,
    TO_AGENTS
  }

  /// The rule a fix follows, wherever an agent reads the commands.
  public static final String RULE = "add or change tests only, never production code";

  /// When not to write a test: a test for a case that can't happen pins code nobody needs pinned,
  /// and an agent told only to close gaps will contrive one.
  public static final String DECLINE =
      "if a mutant's case can't happen in practice, or the change is one no caller can see, don't"
          + " contrive a test: say why, and propose ignoring it with that reason (and --as"
          + " equivalent, unreachable, telemetry, deprecated or accepted-risk) for a person to"
          + " accept";

  private final String label;
  private final String key;
  private final String arguments;
  private final String outcome;

  private final Shown shown;

  MutantCommand(String label, String key, String arguments, String outcome, Shown shown) {
    this.label = label;
    this.key = key;
    this.arguments = arguments;
    this.outcome = outcome;
    this.shown = shown;
  }

  /// The commands offered with each mutant, in the order shown, as a person reads them.
  public static List<MutantCommand> withAMutant() {
    return List.of(values()).stream()
        .filter(
            command -> command.shown == Shown.EVERYWHERE || command.shown == Shown.WITH_A_MUTANT)
        .toList();
  }

  /// The commands offered with each mutant as a coding agent reads them: a proposal where a person
  /// gets an ignore.
  public static List<MutantCommand> toAnAgent() {
    return List.of(values()).stream()
        .filter(command -> command.shown == Shown.EVERYWHERE || command.shown == Shown.TO_AGENTS)
        .toList();
  }

  /// `done when`: what a card calls it.
  public String label() {
    return label;
  }

  /// ` passes`: what the command shows when it applies, after it; not part of the command.
  public String outcome() {
    return outcome;
  }

  /// `doneWhen`: its field in the event stream.
  public String key() {
    return key;
  }

  /// `mutastic verify m855c8e3c samples/pricing`, or `mvn mutastic:verify
  /// -Dmutastic.mutant=m855c8e3c -f samples/pricing`, as `invocation` writes it.
  public String command(String id, Invocation invocation) {
    return invocation.command(arguments(id));
  }

  /// `mutastic verify m855c8e3c samples/pricing passes`: the command, and what it shows when it
  /// applies.
  public String text(String id, Invocation invocation) {
    return command(id, invocation) + outcome;
  }

  /// The command for every mutant in `finding`: one run proves or shows them all, but each is
  /// ignored on its own, for its own reason.
  String text(Finding finding, Invocation invocation) {
    if ((this == IGNORE || this == PROPOSE) && finding.mutants().size() > 1) {
      return command("<id>", invocation) + " for each of " + finding.ids().replace(",", ", ");
    }
    return text(finding.ids(), invocation);
  }

  /// The arguments after `mutastic`, for checking they parse: `verify m855c8e3c`.
  public List<String> arguments(String id) {
    return List.of(arguments.formatted(id).split(" ", -1));
  }

  /// `mutastic verify <id> · mutastic run --only=<id> · mutastic explain <id>`: the summary's.
  public static String next(Invocation invocation) {
    return List.of(values()).stream()
        .filter(
            command -> command.shown == Shown.EVERYWHERE || command.shown == Shown.IN_THE_SUMMARY)
        .map(command -> command.command("<id>", invocation))
        .collect(Collectors.joining(" · "));
  }

  /// A path, quoted if a shell needs it to be.
  public static String shell(String path) {
    return Invocation.quoted(path);
  }
}
