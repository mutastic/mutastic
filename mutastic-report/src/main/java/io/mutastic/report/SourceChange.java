package io.mutastic.report;

import java.util.Optional;

/// A mutant's change as source: the span of the file it replaces, the source there, and the source
/// that replaces it, so that writing `replacement` over the span gives the mutant's statement. Both
/// editions give `mutation.original` and `replacement` this way.
///
/// It's found by comparing the statement as written with the statement as the card shows the mutant
/// has it, cut to whole tokens: `total >= 100` and `total > 100` give `>=` and `>`, not `=` and
/// nothing; `if (total >= 100)` and `if (false)` give the condition and `false`.
///
/// @param line the span's first line, 1-based
/// @param column its first column, 1-based
/// @param endLine its last line
/// @param endColumn the column after its last character, 1-based
/// @param original the source in the span
/// @param replacement the source that replaces it: empty for a statement the mutant takes out
public record SourceChange(
    int line, int column, int endLine, int endColumn, String original, String replacement) {

  /// The change a card's statement shows, when it shows one.
  static Optional<SourceChange> of(Diff diff) {
    if (diff.after().isEmpty()) {
      return Optional.empty();
    }
    var span = Span.of(String.join("\n", diff.before()), String.join("\n", diff.after().get()));
    if (span.isEmpty()) {
      return Optional.empty();
    }
    var whole = span.toTokens();
    var before = whole.before();
    int end = before.length() - whole.suffix();
    return Optional.of(
        new SourceChange(
            lineOf(diff.first(), before, whole.prefix()),
            columnOf(before, whole.prefix()),
            lineOf(diff.first(), before, end),
            columnOf(before, end),
            before.substring(whole.prefix(), end),
            whole.replacement()));
  }

  private static int lineOf(int first, String text, int offset) {
    return first + (int) text.substring(0, offset).chars().filter(at -> at == '\n').count();
  }

  private static int columnOf(String text, int offset) {
    return offset - text.lastIndexOf('\n', offset - 1);
  }

  /// The statement before and after, and how much of each they share at the start and the end:
  /// what's between is the change.
  private record Span(String before, String after, int prefix, int suffix) {
    static Span of(String before, String after) {
      int prefix = 0;
      while (prefix < before.length()
          && prefix < after.length()
          && before.charAt(prefix) == after.charAt(prefix)) {
        prefix++;
      }
      int most = Math.min(before.length(), after.length()) - prefix;
      int suffix = 0;
      while (suffix < most
          && before.charAt(before.length() - 1 - suffix)
              == after.charAt(after.length() - 1 - suffix)) {
        suffix++;
      }
      return new Span(before, after, prefix, suffix);
    }

    boolean isEmpty() {
      return prefix + suffix == before.length() && prefix + suffix == after.length();
    }

    /// The span widened to whole tokens, and for a statement taken out, without its indent.
    Span toTokens() {
      var span = this;
      while (span.prefix > 0 && span.continues(span.prefix - 1)) {
        span = new Span(before, after, span.prefix - 1, span.suffix);
      }
      while (span.suffix > 0 && span.continues(before.length() - span.suffix)) {
        span = new Span(before, after, span.prefix, span.suffix - 1);
      }
      if (after.isEmpty()) {
        int indent = before.length() - before.stripLeading().length();
        span = new Span(before, after, indent, span.suffix);
      }
      return span;
    }

    String replacement() {
      return after.isEmpty() ? "" : after.substring(prefix, after.length() - suffix);
    }

    /// Whether the shared character at `outside`, next to the change, belongs to a token the change
    /// cuts through, in either text: then the change takes it in.
    private boolean continues(int outside) {
      var kind = Kind.of(before.charAt(outside));
      boolean left = outside < prefix;
      return kind != Kind.OTHER && (edge(before, left) == kind || edge(after, left) == kind);
    }

    /// The kind of the change's first (or last) character in `text`; `OTHER` when it has none.
    private Kind edge(String text, boolean first) {
      int to = text.length() - suffix;
      if (prefix >= to) {
        return Kind.OTHER;
      }
      return Kind.of(text.charAt(first ? prefix : to - 1));
    }
  }

  /// What a character can be part of: a name or number, an operator, or neither.
  private enum Kind {
    WORD,
    OPERATOR,
    OTHER;

    static Kind of(char character) {
      if (Character.isLetterOrDigit(character) || character == '_' || character == '$') {
        return WORD;
      }
      return "+-*/%<>=!&|^~?:".indexOf(character) >= 0 ? OPERATOR : OTHER;
    }
  }
}
