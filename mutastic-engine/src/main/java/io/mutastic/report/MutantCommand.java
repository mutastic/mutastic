package io.mutastic.report;

import java.util.List;
import java.util.stream.Collectors;

/// The commands every output offers for one mutant, in the order shown. Cards, agent text, the
/// event stream and the summary's `Next:` line all take them from here, so a command added here
/// appears everywhere, and the CLI's tests check that each one is a command the CLI has.
public enum MutantCommand {
  /// How to prove a fix: the mutant is caught.
  DONE_WHEN("done when", "doneWhen", "verify %s", " passes"),
  /// How to see it again on its own.
  REPRODUCE("reproduce", "reproduce", "run --only=%s", "");

  /// The rule a fix follows, wherever an agent reads the commands.
  public static final String RULE = "add or change tests only, never production code";

  private final String label;
  private final String key;
  private final String arguments;
  private final String outcome;

  MutantCommand(String label, String key, String arguments, String outcome) {
    this.label = label;
    this.key = key;
    this.arguments = arguments;
    this.outcome = outcome;
  }

  /// `done when`: what a card calls it.
  public String label() {
    return label;
  }

  /// `doneWhen`: its field in the event stream.
  public String key() {
    return key;
  }

  /// `mutastic verify m855c8e3c`.
  public String command(String id) {
    return "mutastic " + arguments.formatted(id);
  }

  /// `mutastic verify m855c8e3c passes`: the command, and what it shows when it applies.
  public String text(String id) {
    return command(id) + outcome;
  }

  /// The arguments after `mutastic`, for checking they parse: `verify m855c8e3c`.
  public List<String> arguments(String id) {
    return List.of(arguments.formatted(id).split(" "));
  }

  /// `mutastic verify <id> · mutastic run --only=<id>`: every command, for the summary.
  public static String next() {
    return List.of(values()).stream().map(command -> command.command("<id>")).collect(Collectors.joining(" · "));
  }
}
