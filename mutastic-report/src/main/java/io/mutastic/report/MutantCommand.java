package io.mutastic.report;

import java.util.List;
import java.util.stream.Collectors;

/// The commands every output offers for one mutant, in the order shown. Cards, agent text, the
/// event stream and the summary's `Next:` line all take them from here, so a command added here
/// appears everywhere, and the CLI's tests check that each one is a command the CLI has.
///
/// Each is written for the project the run was on (its directory and module, as typed from where
/// mutastic ran), so it works as it's copied: an id means nothing in another project.
public enum MutantCommand {
  /// How to prove a fix: the mutant is caught.
  DONE_WHEN("done when", "doneWhen", "verify %s", " passes", Shown.EVERYWHERE),
  /// How to see it again on its own.
  REPRODUCE("reproduce", "reproduce", "run --only=%s", "", Shown.EVERYWHERE),
  /// How to decide it isn't worth a test: recorded, with the reason, for review.
  IGNORE("or ignore", "ignore", "ignore %s --reason=\"<why>\"", "", Shown.WITH_A_MUTANT),
  /// Everything known of it, for a line that only names it.
  EXPLAIN("explain", "explain", "explain %s", "", Shown.IN_THE_SUMMARY);

  /// Where a command is offered: with each mutant (cards, agent text, events), in the summary's
  /// `Next:` line, or both. A full card explains its mutant, so `explain` is offered for the
  /// one-line formats, by the summary; ignoring isn't a way to a fix, so not there.
  enum Shown {
    EVERYWHERE,
    WITH_A_MUTANT,
    IN_THE_SUMMARY
  }

  /// The rule a fix follows, wherever an agent reads the commands.
  public static final String RULE = "add or change tests only, never production code";

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

  /// The commands offered with each mutant, in the order shown.
  public static List<MutantCommand> withAMutant() {
    return List.of(values()).stream()
        .filter(command -> command.shown != Shown.IN_THE_SUMMARY)
        .toList();
  }

  /// `done when`: what a card calls it.
  public String label() {
    return label;
  }

  /// `doneWhen`: its field in the event stream.
  public String key() {
    return key;
  }

  /// `mutastic verify m855c8e3c samples/pricing`, for the project `target` names (nothing when it's
  /// the directory mutastic ran in and its root project).
  public String command(String id, List<String> target) {
    var words = new java.util.ArrayList<>(List.of("mutastic", arguments.formatted(id)));
    target.forEach(word -> words.add(quoted(word)));
    return String.join(" ", words);
  }

  /// `mutastic verify m855c8e3c samples/pricing passes`: the command, and what it shows when it
  /// applies.
  public String text(String id, List<String> target) {
    return command(id, target) + outcome;
  }

  /// The arguments after `mutastic`, for checking they parse: `verify m855c8e3c`.
  public List<String> arguments(String id) {
    return List.of(arguments.formatted(id).split(" ", -1));
  }

  /// `mutastic verify <id> · mutastic run --only=<id> · mutastic explain <id>`: the summary's.
  public static String next(List<String> target) {
    return List.of(values()).stream()
        .filter(command -> command.shown != Shown.WITH_A_MUTANT)
        .map(command -> command.command("<id>", target))
        .collect(Collectors.joining(" · "));
  }

  /// A path with a space or a quote, quoted for a shell.
  private static String quoted(String word) {
    return word.matches("[A-Za-z0-9_./:@%+=,-]+") ? word : "'" + word.replace("'", "'\\''") + "'";
  }
}
