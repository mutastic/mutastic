package io.mutastic.cli;

import java.util.List;
import java.util.regex.Pattern;

/// The `+` lines of cards, where a reader takes what's written for the mutant's code.
final class PlusLines {
  /// A `+` line, as an agent or a person reads it.
  private static final Pattern PLUS_LINE =
      Pattern.compile("(?m)^\\s*(?:= mutant\\s+)?\\+ (?<code>.*)$");

  /// Words in brackets, `(changed to endsWith)`: a description, where only code should be.
  private static final Pattern DESCRIBED =
      Pattern.compile("\\((?:[^()]*`[^()]*|[a-z]+(?: [a-z]+)+)\\)");

  private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

  private PlusLines() {}

  /// The `+` lines in cards that describe the change in brackets instead of showing the code: none
  /// should, as a reader takes that line for code. A change the source can't show is said on a line
  /// of its own, `change`.
  static List<String> describedInBrackets(String cards) {
    return PLUS_LINE
        .matcher(ANSI.matcher(cards).replaceAll(""))
        .results()
        .map(line -> line.group("code").strip())
        .filter(code -> DESCRIBED.matcher(code).matches())
        .toList();
  }
}
