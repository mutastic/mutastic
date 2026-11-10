package io.mutastic.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// What a card shows of a mutant's change: the lines of the statement it's in, as written and as
/// the mutant has them, when the source can show it; otherwise the change in words.
///
/// @param first the statement's first line, 1-based; 0 when the source isn't there
/// @param before the statement's lines as written, as in the file
/// @param after the same lines as the mutant has them: none for a statement the mutant takes out;
///   empty when the source can't show the change
/// @param change the change in words
public record Diff(int first, List<String> before, Optional<List<String>> after, String change) {
  /// The most of a statement a card shows; a longer one is cut short, around its change.
  static final int MOST_LINES = 6;

  /// What a card shows for lines it leaves out of a long statement.
  static final String ELLIPSIS = "…";

  public Diff {
    before = List.copyOf(before);
    after = after.map(List::copyOf);
  }

  /// Whether the card can show the mutant's lines.
  public boolean shown() {
    return after.isPresent();
  }

  /// Whether the mutant takes the statement out.
  public boolean removed() {
    return after.filter(List::isEmpty).isPresent();
  }

  /// The statement's last line.
  public int last() {
    return first + before.size() - 1;
  }

  /// The lines as written, as a card shows them: indented as the statement is, at most
  /// [#MOST_LINES], around the change.
  public List<String> shownBefore() {
    return cut(before, changedAt(before, after.orElse(List.of())));
  }

  /// The lines as the mutant has them, as a card shows them.
  public List<String> shownAfter() {
    var lines = after.orElse(List.of());
    return cut(lines, changedAt(lines, before));
  }

  /// A file's lines with the statement's replaced by the mutant's, each changed line through
  /// `changed`: for checking what a card shows by compiling it.
  public List<String> appliedTo(
      List<String> all, java.util.function.UnaryOperator<String> changed) {
    var lines = new ArrayList<>(all.subList(0, first - 1));
    after.orElseThrow().forEach(line -> lines.add(changed.apply(line)));
    lines.addAll(all.subList(last(), all.size()));
    return lines;
  }

  /// The change in words, for a card that can't show the mutant's lines.
  public String unshown() {
    return change + " (not shown in the source)";
  }

  /// The first line the mutant changes, by where its lines and the written ones first differ.
  private static int changedAt(List<String> lines, List<String> other) {
    int at = 0;
    while (at < lines.size() && at < other.size() && lines.get(at).equals(other.get(at))) {
      at++;
    }
    return Math.min(at, Math.max(0, lines.size() - 1));
  }

  /// The lines without the indent they all have, and only [#MOST_LINES] of them, from a little
  /// before `changed`, with [#ELLIPSIS] for what's left out.
  private List<String> cut(List<String> lines, int changed) {
    int indent = indent();
    var shown = new ArrayList<String>();
    int from = Math.max(0, Math.min(changed - 1, lines.size() - MOST_LINES));
    int to = Math.min(lines.size(), from + MOST_LINES);
    if (from > 0) {
      shown.add(ELLIPSIS);
    }
    for (var line : lines.subList(from, to)) {
      shown.add(line.substring(Math.min(indent, leading(line))).stripTrailing());
    }
    if (to < lines.size()) {
      shown.add(ELLIPSIS);
    }
    return shown;
  }

  /// The indent every written line has.
  private int indent() {
    return before.stream().filter(line -> !line.isBlank()).mapToInt(Diff::leading).min().orElse(0);
  }

  private static int leading(String line) {
    return line.length() - line.stripLeading().length();
  }
}
