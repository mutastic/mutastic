package io.mutastic.report;

import java.util.List;

/// What `mutastic next` says: the head of the [Queue], or with `--top` its first few, each with why
/// it's ranked where it is. For an agent, one task, whole: the gap, why it's first, the test to
/// write, how it's done, how to decline it, and to ask for the next one after, so an agent can work
/// a backlog one `next` at a time.
public final class NextText {
  private final SourceView sources;
  private final HumanText human;
  private final Style style;
  private final Vocabulary words;

  public NextText(SourceView sources, HumanText human, Style style, Vocabulary words) {
    this.sources = sources;
    this.human = human;
    this.style = style;
    this.words = words;
  }

  /// For people: how many are queued, then each item's card with why it's ranked there.
  public String human(Queue queue, String project, int count) {
    if (queue.findings().isEmpty()) {
      return "Next: " + nothingLeft(project) + "\n";
    }
    var shown = queue.top(count);
    var out = new StringBuilder();
    out.append(style.bold("Next: " + header(queue, project, shown.size()))).append('\n');
    for (int at = 0; at < shown.size(); at++) {
      var finding = shown.get(at);
      out.append(human.card(finding));
      out.append(' ').append(style.bold(rankedBecause(at + 1))).append('\n');
      queue.factors(finding).forEach(factor -> out.append("   ").append(factor.why()).append('\n'));
    }
    out.append("%nThen: %s, for the next one%n".formatted(command()));
    return out.toString();
  }

  /// For an agent: one task, the head of the queue, whole.
  public String agent(Queue queue, String project) {
    if (queue.findings().isEmpty()) {
      return "mutastic next: " + nothingLeft(project) + "\n";
    }
    var head = queue.findings().getFirst();
    var out = new StringBuilder();
    out.append(
        "mutastic next: %s; close this one, then ask for the next%n"
            .formatted(header(queue, project, 1)));
    out.append("why first: %s%n".formatted(whyFirst(queue, head)));
    out.append(rules(head));
    out.append(new AgentText(sources, 0, words).entry(0, head));
    out.append(
        "%nthen: run `%s` again for the next one; when it says nothing is left, you're done%n"
            .formatted(command()));
    return out.toString();
  }

  /// The rules a fix follows, when it's done, and how to decline it instead.
  private String rules(Finding head) {
    return ("rules: %s, and never delete, disable or loosen a test; it's done when `%s` passes%n"
            + "decline: %s (`%s`)%n%n")
        .formatted(
            MutantCommand.RULE,
            MutantCommand.DONE_WHEN.command(head.ids(), sources.invocation()),
            MutantCommand.DECLINE,
            MutantCommand.PROPOSE.command(head.lead().mutant().id(), sources.invocation()));
  }

  private static String whyFirst(Queue queue, Finding head) {
    return String.join("; ", queue.factors(head).stream().map(Queue.Factor::why).toList());
  }

  /// `1 of 12 lines with missed mutants in pricing`, and how many are new where there's a baseline
  /// to say.
  String header(Queue queue, String project, int shown) {
    int all = queue.findings().size();
    int legacy = queue.legacyCount();
    return "%s of %d line%s with %s mutants in %s%s"
        .formatted(
            shown == 1 ? "1" : "the first " + shown,
            all,
            all == 1 ? "" : "s",
            words.undetected,
            project,
            queue.legacy().isEmpty()
                ? ""
                : " (%d new, %d in the baseline)".formatted(all - legacy, legacy));
  }

  private String nothingLeft(String project) {
    return "nothing %s is left in %s".formatted(words.undetected, project);
  }

  /// `Ranked first because`, `Ranked 2nd because`.
  static String rankedBecause(int position) {
    return "Ranked %s because".formatted(position == 1 ? "first" : ordinal(position));
  }

  /// `2nd`, `11th`, `23rd`.
  static String ordinal(int number) {
    int tens = number % 100;
    if (tens >= 11 && tens <= 13) {
      return number + "th";
    }
    return number
        + switch (number % 10) {
          case 1 -> "st";
          case 2 -> "nd";
          case 3 -> "rd";
          default -> "th";
        };
  }

  /// `mutastic next`, for the project the run was on.
  private String command() {
    return sources.invocation().command(List.of("next"));
  }
}
