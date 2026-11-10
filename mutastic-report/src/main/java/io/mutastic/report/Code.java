package io.mutastic.report;

/// Reading Java or Kotlin source as text, for a card's changed lines: what's code and not a string
/// or a comment, and where brackets and expressions start and end.
final class Code {
  private Code() {}

  /// The text with each string, character literal and comment covered over, each character replaced
  /// so positions stay where they were: a literal by `_`, so it reads as one name, a comment by
  /// spaces.
  static String masked(String text) {
    var out = new StringBuilder(text);
    int at = 0;
    while (at < text.length()) {
      char each = text.charAt(at);
      if (each == '"' || each == '\'') {
        at = cover(out, at, literalEnd(text, at), '_');
      } else if (text.startsWith("//", at)) {
        int end = text.indexOf('\n', at);
        at = cover(out, at, end < 0 ? text.length() : end, ' ');
      } else if (text.startsWith("/*", at)) {
        int end = text.indexOf("*/", at + 2);
        at = cover(out, at, end < 0 ? text.length() : end + 2, ' ');
      } else {
        at++;
      }
    }
    return out.toString();
  }

  private static int cover(StringBuilder out, int from, int to, char with) {
    for (int at = from; at < to; at++) {
      if (out.charAt(at) != '\n') {
        out.setCharAt(at, with);
      }
    }
    return to;
  }

  /// Just past the literal that starts at `at`, its escapes skipped; the line's end if it doesn't
  /// close.
  private static int literalEnd(String text, int at) {
    char quote = text.charAt(at);
    int end = at + 1;
    while (end < text.length() && text.charAt(end) != quote && text.charAt(end) != '\n') {
      end += text.charAt(end) == '\\' ? 2 : 1;
    }
    return Math.min(end + 1, text.length());
  }

  /// How a character changes how deep in brackets the code is.
  static int depthChange(char c) {
    return switch (c) {
      case '(', '[', '{' -> 1;
      case ')', ']', '}' -> -1;
      default -> 0;
    };
  }

  /// The bracket that closes the one at `open`, in masked code, or -1.
  static int closing(String code, int open) {
    int depth = 0;
    for (int at = open; at < code.length(); at++) {
      depth += depthChange(code.charAt(at));
      if (depth == 0) {
        return at;
      }
    }
    return -1;
  }

  /// The bracket that opens the one that closes at `close`, in masked code, or -1.
  private static int opening(String code, int close) {
    int depth = 0;
    for (int at = close; at >= 0; at--) {
      depth -= depthChange(code.charAt(at));
      if (depth == 0) {
        return at;
      }
    }
    return -1;
  }

  /// Where the expression a member is selected from starts: `repo.find(id)` in
  /// `repo.find(id).orElse(x)`, from the `.` before `orElse`; with `new` if it's a new object.
  static int expressionStart(String code, int dot) {
    int start = dot;
    int at = skipSpaceBack(code, dot - 1);
    while (at >= 0) {
      int primary = primaryStart(code, at);
      if (primary > at) {
        break;
      }
      start = primary;
      int before = skipSpaceBack(code, primary - 1);
      if (before < 0 || code.charAt(before) != '.') {
        break;
      }
      at = skipSpaceBack(code, before - 1);
    }
    return withNew(code, start);
  }

  /// Where a name, or a run of names and brackets with nothing between, ends at `end`, starts; past
  /// `end` when there's none.
  private static int primaryStart(String code, int end) {
    int at = end;
    while (at >= 0) {
      char each = code.charAt(at);
      if (each == ')' || each == ']') {
        int open = opening(code, at);
        if (open < 0) {
          break;
        }
        at = open - 1;
      } else if (Character.isJavaIdentifierPart(each)) {
        at--;
      } else {
        break;
      }
    }
    return at + 1;
  }

  private static int skipSpaceBack(String code, int at) {
    int before = at;
    while (before >= 0 && Character.isWhitespace(code.charAt(before))) {
      before--;
    }
    return before;
  }

  /// `start`, or the `new` before it.
  private static int withNew(String code, int start) {
    int before = skipSpaceBack(code, start - 1);
    boolean isNew =
        before >= 2
            && code.startsWith("new", before - 2)
            && (before < 3 || !Character.isJavaIdentifierPart(code.charAt(before - 3)));
    return isNew ? before - 2 : start;
  }

  /// Whether the code is an operation on values, which may need brackets where an operand stood: a
  /// space outside brackets, or a sign or cast in front.
  static boolean isOperation(String text) {
    var code = masked(text);
    if (code.startsWith("-") || code.startsWith("(")) {
      return true;
    }
    int depth = 0;
    for (int at = 0; at < code.length(); at++) {
      depth += depthChange(code.charAt(at));
      if (depth == 0 && code.charAt(at) == ' ') {
        return true;
      }
    }
    return false;
  }

  /// A call: a name, or what ends one (`>` of a type argument, `]`), before a bracket.
  private static final java.util.regex.Pattern CALL =
      java.util.regex.Pattern.compile("[\\w$>\\]]\\s*\\(|\\bnew\\b");

  /// An assignment, `++` or `--`.
  private static final java.util.regex.Pattern ASSIGNS =
      java.util.regex.Pattern.compile("\\+\\+|--|(?<![=!<>])=(?![=>])");

  /// A lambda, `x -> …` or `(a, b) -> …`, or a method reference on a name, `Type::name`: code that
  /// does nothing until it's called.
  private static final java.util.regex.Pattern DEFERRED =
      java.util.regex.Pattern.compile("(?s)^(?:\\([^()]*\\)|[\\w$]+)\\s*->.*|^[\\w$.]+::[\\w$]+$");

  /// A variable, field or array element written to: what an assignment, `++` or `--` can change.
  private static final String WRITTEN = "[\\w$]+(?:\\s*\\.\\s*[\\w$]+|\\[[^\\[\\]]*])*";

  /// An expression Java runs as a statement of its own: an assignment, `++` or `--`.
  private static final java.util.regex.Pattern WRITES =
      java.util.regex.Pattern.compile(
          "(?s)^(?:%s\\s*(?:[-+*/%%&|^]|<<|>>>?)?=(?!=).+|%s\\s*(?:\\+\\+|--)|(?:\\+\\+|--)\\s*%s)$"
              .formatted(WRITTEN, WRITTEN, WRITTEN));

  /// The code as a statement of its own, when it can be one: a call, a new object, an assignment,
  /// `++` or `--`, without brackets around it all, which a statement can't have. `(next())` is
  /// `next()`; `a + next()` is none.
  static java.util.Optional<String> asStatement(String text) {
    var part = trimmed(text);
    var code = masked(part);
    while (code.startsWith("(") && closing(code, 0) == code.length() - 1) {
      part = trimmed(part.substring(1, part.length() - 1));
      code = masked(part);
    }
    return isOneCall(code) || WRITES.matcher(code).matches()
        ? java.util.Optional.of(part)
        : java.util.Optional.empty();
  }

  /// The text without the space and comments before and after its code.
  private static String trimmed(String text) {
    var code = masked(text);
    int from = 0;
    while (from < code.length() && Character.isWhitespace(code.charAt(from))) {
      from++;
    }
    int to = code.length();
    while (to > from && Character.isWhitespace(code.charAt(to - 1))) {
      to--;
    }
    return text.substring(from, to);
  }

  /// Whether masked code is one call, or one new object, maybe at the end of a chain of members:
  /// nothing outside brackets is space, but around a `.`.
  private static boolean isOneCall(String code) {
    if (!code.endsWith(")")) {
      return false;
    }
    var chained = code.replaceAll("\\s*\\.\\s*", ".");
    var withoutNew = chained.startsWith("new ") ? chained.substring(4) : chained;
    int depth = 0;
    for (int at = 0; at < withoutNew.length(); at++) {
      char each = withoutNew.charAt(at);
      depth += depthChange(each);
      if (depth == 0 && (Character.isWhitespace(each) || "+-*/%<>=!&|^?:".indexOf(each) >= 0)) {
        return false;
      }
    }
    return true;
  }

  /// Whether working out the code does something a card couldn't show it leaving out: it calls a
  /// method, makes an object or assigns. A lambda or a method reference does nothing until called.
  static boolean doesSomething(String text) {
    var code = masked(text).strip();
    return !DEFERRED.matcher(code).matches()
        && (CALL.matcher(code).find() || ASSIGNS.matcher(code).find());
  }
}
