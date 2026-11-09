package io.mutastic.report;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/// Operands as the source writes them, where javac wrote a constant in: `msg.equals(EXIT_CODE)`
/// where the bytecode, which inlines a `static final String` (or a `final` local set to one), has
/// `msg.equals("{}")`. A string is read off the statement by what's around it: after the call it's
/// an argument of, or before the call it's the receiver of. It stays as it is when the statement
/// doesn't say. A number keeps its value: the input a card suggests is that value.
final class WrittenOperands {
  /// A string, as an operand quotes it.
  private static final Pattern STRING = Pattern.compile("\"(?<text>[^\"]*)\"");

  /// A name, or one with dots, `TcpProtocol.EXIT_CODE`, at the end of what's before a call.
  private static final Pattern NAME_BEFORE =
      Pattern.compile(
          "(?<![\\w$.])(?<!\\.\\s)(?<name>[A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)\\s*$");

  private WrittenOperands() {}

  /// `operands`, each with its strings as `statement` writes them.
  static List<String> of(List<String> operands, String statement) {
    return operands.stream().map(operand -> of(operand, statement)).toList();
  }

  /// One operand, with its strings as `statement` writes them.
  static String of(String operand, String statement) {
    var written = new StringBuilder();
    var strings = STRING.matcher(operand);
    int from = 0;
    while (strings.find()) {
      written.append(operand, from, strings.start());
      var after = operand.substring(strings.end());
      written.append(
          argument(written.toString(), after, statement)
              .or(() -> receiver(after, statement))
              .orElse(strings.group()));
      from = strings.end();
    }
    return written.append(operand.substring(from)).toString();
  }

  /// `EXIT_CODE` in `msg.equals(EXIT_CODE)`: what the statement has after `msg.equals(`, up to the
  /// `)` or `,` the operand has next.
  private static Optional<String> argument(String before, String after, String statement) {
    if (after.isEmpty() || !(before.endsWith("(") || before.endsWith(", "))) {
      return Optional.empty();
    }
    var end = onlyEnd(spaced(before, true), statement);
    return end.isPresent()
        ? argumentFrom(statement, end.getAsInt(), after.charAt(0))
        : Optional.<String>empty();
  }

  /// `TcpProtocol.EXIT_CODE` in `TcpProtocol.EXIT_CODE.equals(msg)`: the name the statement has
  /// before the call the operand makes on the string.
  private static Optional<String> receiver(String after, String statement) {
    if (!after.startsWith(".")) {
      return Optional.empty();
    }
    var call = spaced(after, false).matcher(statement);
    if (!call.find()) {
      return Optional.empty();
    }
    int start = call.start();
    if (call.find()) {
      return Optional.empty();
    }
    var name = NAME_BEFORE.matcher(statement.substring(0, start));
    return name.find()
        ? Optional.of(name.group("name").replaceAll("\\s+", ""))
        : Optional.<String>empty();
  }

  /// The argument that starts at `from`: up to the `,` or `)` that ends it, when that's `ends`.
  private static Optional<String> argumentFrom(String statement, int from, char ends) {
    int depth = 0;
    for (int at = from; at < statement.length(); at++) {
      char next = statement.charAt(at);
      if (next == '"' || next == '\'') {
        at = closing(statement, at);
      } else if (depth == 0 && (next == ',' || next == ')')) {
        var argument = statement.substring(from, at).strip().replaceAll("\\s+", " ");
        return next == ends && !argument.isEmpty() ? Optional.of(argument) : Optional.empty();
      } else if (next == ';') {
        return Optional.empty();
      } else {
        depth += nesting(next);
      }
    }
    return Optional.empty();
  }

  /// Where the string or character opened at `open` closes.
  private static int closing(String text, int open) {
    char quote = text.charAt(open);
    int at = open + 1;
    while (at < text.length() && text.charAt(at) != quote) {
      at += text.charAt(at) == '\\' ? 2 : 1;
    }
    return at;
  }

  /// How far `c` takes the nesting in or out.
  private static int nesting(char c) {
    return switch (c) {
      case '(', '[', '{' -> 1;
      case ')', ']', '}' -> -1;
      default -> 0;
    };
  }

  /// Where the one place `pattern` matches in `text` ends, if it matches once.
  private static OptionalInt onlyEnd(Pattern pattern, String text) {
    var found = pattern.matcher(text);
    if (!found.find()) {
      return OptionalInt.empty();
    }
    int end = found.end();
    return found.find() ? OptionalInt.empty() : OptionalInt.of(end);
  }

  /// `text` as a pattern that takes any spacing between its characters: the source may split a call
  /// over lines. Not run on from a longer name, when `whole`.
  private static Pattern spaced(String text, boolean whole) {
    var pattern = new StringBuilder(whole ? "(?<![\\w$])" : "");
    text.codePoints()
        .filter(point -> !Character.isWhitespace(point))
        .forEach(point -> pattern.append(Pattern.quote(Character.toString(point))).append("\\s*"));
    return Pattern.compile(pattern.toString());
  }
}
