package io.mutastic.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A call as a statement's source writes it, found by the method's name: what it's called on, its
/// arguments, and where each is, so a change to the call can be written into the source.
///
/// @param text the statement
/// @param start where the call starts: what it's called on, or its name when that's all there is
/// @param dot the `.` before its name, or -1 when it's called on nothing written
/// @param open its `(`
/// @param close its `)`
/// @param arguments where each argument is, between its commas
record CallText(String text, int start, int dot, int open, int close, List<Span> arguments) {
  /// Part of the text, `from` to `to`, the end exclusive.
  record Span(int from, int to) {}

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(?<part>receiver|args|arg\\d+)}");

  CallText {
    arguments = List.copyOf(arguments);
  }

  /// The call to `name` in `text`, outside strings and comments, with brackets that close: the one
  /// on javac's line, which starts at `from`, when that line has one; else the one after it, when
  /// there's one. javac gives a call on a later line of a statement that line, so the line says
  /// which of two calls of one name it is.
  static Optional<CallText> find(String text, int from, String name) {
    var code = Code.masked(text);
    var calls = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "\\s*\\(").matcher(code);
    int lineEnd = code.indexOf('\n', from) < 0 ? code.length() : code.indexOf('\n', from);
    var onTheLine = onlyMatch(calls.region(from, lineEnd));
    if (onTheLine.isEmpty() && !calls.region(from, lineEnd).find()) {
      onTheLine = onlyMatch(calls.region(from, code.length()));
    }
    return onTheLine.flatMap(found -> at(text, code, found));
  }

  /// Where the matcher's only match ends, the `(`, if it has exactly one.
  private static Optional<Integer> onlyMatch(Matcher matcher) {
    if (!matcher.find()) {
      return Optional.empty();
    }
    int open = matcher.end() - 1;
    return matcher.find() ? Optional.empty() : Optional.of(open);
  }

  /// The call whose `(` is at `open`.
  private static Optional<CallText> at(String text, String code, int open) {
    int close = Code.closing(code, open);
    if (close < 0) {
      return Optional.empty();
    }
    int nameStart = open;
    while (nameStart > 0 && Character.isWhitespace(code.charAt(nameStart - 1))) {
      nameStart--;
    }
    while (nameStart > 0 && Character.isJavaIdentifierPart(code.charAt(nameStart - 1))) {
      nameStart--;
    }
    int dot = dotBefore(code, nameStart);
    int start = dot < 0 ? nameStart : Code.expressionStart(code, dot);
    return Optional.of(new CallText(text, start, dot, open, close, split(code, open, close)));
  }

  /// The `.` before a name, across the line breaks of a chain, or -1.
  private static int dotBefore(String code, int at) {
    int before = at - 1;
    while (before >= 0 && Character.isWhitespace(code.charAt(before))) {
      before--;
    }
    return before >= 0 && code.charAt(before) == '.' ? before : -1;
  }

  /// The arguments between `open` and `close`, split at the commas outside brackets.
  private static List<Span> split(String code, int open, int close) {
    var spans = new ArrayList<Span>();
    if (code.substring(open + 1, close).isBlank()) {
      return spans;
    }
    int depth = 0;
    int from = open + 1;
    for (int at = open + 1; at < close; at++) {
      char each = code.charAt(at);
      depth += Code.depthChange(each);
      if (each == ',' && depth == 0) {
        spans.add(new Span(from, at));
        from = at + 1;
      }
    }
    spans.add(new Span(from, close));
    return spans;
  }

  private String receiver() {
    return dot < 0 ? "" : text.substring(start, dot).strip();
  }

  private String argument(int index) {
    var span = arguments.get(index);
    return text.substring(span.from(), span.to()).strip();
  }

  /// The statement with the call written as `template` has it (see
  /// [io.mutastic.api.Mutator.Words#written]); empty when the template needs a part the call
  /// hasn't, or drops one that changes something, as an assignment does.
  Optional<String> rewritten(String template) {
    boolean keepsReceiver = template.startsWith("{receiver}") && dot >= 0;
    var shown = keepsReceiver ? template.substring("{receiver}".length()) : template;
    if (dot < 0 && shown.startsWith("{receiver}.")) {
      shown = shown.substring("{receiver}.".length());
    }
    var filled = filled(shown);
    if (filled.isEmpty() || dropsAnEffect(template, keepsReceiver)) {
      return Optional.empty();
    }
    int from = keepsReceiver ? dot : start;
    var replacement =
        keepsReceiver || !needsBrackets(filled.get(), from)
            ? filled.get()
            : "(" + filled.get() + ")";
    return Optional.of(text.substring(0, from) + replacement + text.substring(close + 1));
  }

  /// The statement with the call given other arguments, in the order given: from the ones it has,
  /// by index.
  Optional<String> withArguments(List<Integer> order) {
    var kept = order.stream().map(index -> "{arg" + (index + 1) + "}").toList();
    var name = text.substring(nameStart(), open).strip();
    return rewritten("{receiver}.%s(%s)".formatted(name, String.join(", ", kept)));
  }

  /// An array made where it's passed: `new String[] {a, b}`.
  private static final Pattern NEW_ARRAY =
      Pattern.compile("^\\s*new\\s+[\\w$.<>]+\\s*(?:\\[\\s*]\\s*)+\\{");

  /// The statement with the array made for the call one value short: its last argument's, when
  /// that's an array the source makes there, `new String[] {a, b}`, or else its last argument, as a
  /// varargs call's array is.
  Optional<String> lastValueDropped() {
    if (arguments.isEmpty()) {
      return Optional.empty();
    }
    var code = Code.masked(text);
    var last = arguments.getLast();
    var array = NEW_ARRAY.matcher(code.substring(last.from(), last.to()));
    if (!array.find()) {
      return withArguments(
          java.util.stream.IntStream.range(0, arguments.size() - 1).boxed().toList());
    }
    int open = last.from() + array.end() - 1;
    int close = Code.closing(code, open);
    var elements = split(code, open, close);
    if (close < 0 || elements.isEmpty()) {
      return Optional.empty();
    }
    var dropped = elements.getLast();
    int from = elements.size() > 1 ? elements.get(elements.size() - 2).to() : open + 1;
    return Code.doesSomething(text.substring(dropped.from(), dropped.to()))
        ? Optional.empty()
        : Optional.of(text.substring(0, from) + text.substring(close));
  }

  /// The statement without it, when the call is all the statement does: `audit.record(x);`.
  Optional<String> removedStatement(boolean kotlin) {
    var after = text.substring(close + 1).strip();
    boolean whole =
        text.substring(0, start).isBlank() && (after.equals(";") || (kotlin && after.isEmpty()));
    var dropped = new ArrayList<String>();
    if (dot >= 0) {
      dropped.add(receiver());
    }
    for (int index = 0; index < arguments.size(); index++) {
      dropped.add(argument(index));
    }
    return whole && dropped.stream().noneMatch(Code::doesSomething)
        ? Optional.of("")
        : Optional.empty();
  }

  int argumentCount() {
    return arguments.size();
  }

  private int nameStart() {
    int at = open;
    while (at > 0 && Character.isWhitespace(text.charAt(at - 1))) {
      at--;
    }
    while (at > 0 && Character.isJavaIdentifierPart(text.charAt(at - 1))) {
      at--;
    }
    return at;
  }

  private Optional<String> filled(String template) {
    var parts = PLACEHOLDER.matcher(template);
    var out = new StringBuilder();
    while (parts.find()) {
      var part = part(parts.group("part"));
      if (part.isEmpty()) {
        return Optional.empty();
      }
      parts.appendReplacement(
          out, Matcher.quoteReplacement(bracketed(parts, template, part.get())));
    }
    parts.appendTail(out);
    return Optional.of(out.toString());
  }

  /// An argument that's an operation, bracketed where the template puts an operator beside it: `(a
  /// + b) * 2`, but not `max(a + b, c)`.
  private static String bracketed(Matcher parts, String template, String value) {
    var before = template.substring(0, parts.start()).stripTrailing();
    var after = template.substring(parts.end()).stripLeading();
    boolean alone =
        (before.isEmpty() || before.endsWith("(") || before.endsWith(","))
            && (after.isEmpty() || after.startsWith(")") || after.startsWith(","));
    return !alone && parts.group().startsWith("{arg") && Code.isOperation(value)
        ? "(" + value + ")"
        : value;
  }

  private Optional<String> part(String name) {
    return switch (name) {
      case "receiver" -> dot < 0 ? Optional.empty() : Optional.of(receiver());
      case "args" -> Optional.of(text.substring(open + 1, close));
      default -> {
        int index = Integer.parseInt(name.substring("arg".length())) - 1;
        yield index < arguments.size() ? Optional.of(argument(index)) : Optional.empty();
      }
    };
  }

  /// Whether a part the template leaves out holds an assignment, `++` or `--`, which would still
  /// happen.
  private boolean dropsAnEffect(String template, boolean keepsReceiver) {
    var dropped = new ArrayList<String>();
    if (dot >= 0 && !keepsReceiver && !template.contains("{receiver}")) {
      dropped.add(receiver());
    }
    if (!template.contains("{args}")) {
      for (int index = 0; index < arguments.size(); index++) {
        if (!template.contains("{arg" + (index + 1) + "}")) {
          dropped.add(argument(index));
        }
      }
    }
    return dropped.stream().anyMatch(Code::doesSomething);
  }

  /// Whether an operation put where the call was needs brackets to keep its meaning: anywhere but a
  /// whole argument, value or statement.
  private boolean needsBrackets(String replacement, int from) {
    if (!Code.isOperation(replacement)) {
      return false;
    }
    var before = text.substring(0, from).stripTrailing();
    var after = text.substring(close + 1).stripLeading();
    boolean wholeBefore =
        before.isEmpty()
            || "(,=;{".indexOf(before.charAt(before.length() - 1)) >= 0
            || before.endsWith("return")
            || before.endsWith("->");
    boolean wholeAfter = after.isEmpty() || "),;}".indexOf(after.charAt(0)) >= 0;
    return !(wholeBefore && wholeAfter);
  }
}
