package io.mutastic.report;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Mutator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/// A mutant's statement as the mutant has it, written as source: each operator's change made in the
/// text where the source shows it exactly, and nothing where it doesn't, so a card never shows a
/// line that isn't the mutant's.
///
/// @param text the statement's lines, joined
/// @param from where javac's line for the mutant starts in it: the change is there or after
/// @param kotlin whether the source is Kotlin, whose statements needn't end in `;`
record ChangedSource(String text, int from, boolean kotlin) {
  /// A removed condition's change, `` `balance > 0` is always true ``: the condition and its value.
  private static final Pattern ALWAYS =
      Pattern.compile("`(?<condition>[^`]+)` is always (?<value>true|false)");

  /// A value negated as it's written, `!true` or `!(false)`.
  private static final Pattern NOT_A_VALUE =
      Pattern.compile("!\\s*(?:\\(\\s*(?<bracketed>true|false)\\s*\\)|(?<bare>true|false)\\b)");

  /// A minus that negates what follows it: after an operator, a bracket or `return`, and not in
  /// front of a number, whose minus is the number's.
  private static final Pattern UNARY_MINUS =
      Pattern.compile(
          "(?:^|[(=,:?*/%<>!&|^{\\[+]|\\breturn)\\s*(?<minus>-)(?=\\s*[\\w$(])(?!\\s*\\d)");

  /// The statement with the mutant's change made; empty text when the mutant takes the statement
  /// out, and nothing when the source can't show the change.
  Optional<String> changed(Mutant mutant, Advice advice) {
    return switch (mutant.operator()) {
      case BuiltIn builtIn -> builtIn(builtIn, mutant, advice);
      case Mutator mutator -> call(mutant, mutator.words(mutant).written());
      default -> Optional.empty();
    };
  }

  private Optional<String> builtIn(BuiltIn operator, Mutant mutant, Advice advice) {
    return switch (operator) {
      case CONSTANT_VALUE -> constant(advice);
      case REMOVE_CONDITIONAL -> conditionReplaced(advice).map(ChangedSource::simplified);
      case REMOVE_VOID_CALL, REMOVE_DISCARDED_CALL ->
          callNamed(mutant).flatMap(call -> call.removedStatement(kotlin));
      case OMIT_CALL ->
          call(
              mutant,
              Optional.of(mutant.replacement().endsWith("receiver") ? "{receiver}" : "{arg1}"));
      case SWAP_ARGUMENTS -> callNamed(mutant).flatMap(ChangedSource::swapped);
      case DROP_VARARG -> callNamed(mutant).flatMap(CallText::lastValueDropped);
      case RETURN_VALUE -> returned(advice);
      case REMOVE_ASSIGNMENT, REMOVE_FIELD_WRITE -> storeRemoved(mutant);
      case FLIP_INCREMENT -> flipped(mutant);
      case REMOVE_NEGATION -> negationRemoved();
      case KEEP_OPERAND -> operandKept(mutant);
      case STRING_CONSTANT, REGEX_CONSTANT -> literalChanged(mutant);
      case REMOVE_THROW -> throwRemoved();
      case REMOVE_SWITCH_ARM -> armRemoved(mutant);
      case DROP_CATCH_TYPE -> catchTypeDropped(mutant);
      case NULL_PARAMETER, REPLACE_BODY -> Optional.empty();
      default -> operatorChanged(mutant, advice);
    };
  }

  private Optional<CallText> callNamed(Mutant mutant) {
    return CallText.find(text, from, SourceView.calledName(mutant.original()));
  }

  private Optional<String> call(Mutant mutant, Optional<String> written) {
    return written.flatMap(template -> callNamed(mutant).flatMap(call -> call.rewritten(template)));
  }

  /// `copy(from, to)` as `copy(to, from)`: its last two arguments the other way round.
  private static Optional<String> swapped(CallText call) {
    int count = call.argumentCount();
    if (count < 2) {
      return Optional.empty();
    }
    var order = new java.util.ArrayList<>(IntStream.range(0, count).boxed().toList());
    order.set(count - 2, count - 1);
    order.set(count - 1, count - 2);
    return call.withArguments(order);
  }

  /// The number changed, when it's in the statement once and not in a string: one the source names,
  /// `LIMIT`, isn't there to change.
  private Optional<String> constant(Advice advice) {
    var before = advice.before().orElseThrow();
    var after = advice.after().orElseThrow();
    return onceInCode(before).map(at -> replaced(at, before.length(), after));
  }

  /// The comparison, calculation or connector changed: `b > 1` found once, from the operands, so a
  /// condition split over lines changes where the mutant is; otherwise (the operands unknown, or a
  /// constant the source names) only an operator the statement has once; or for a calculation, its
  /// compound assignment, `+=`.
  private Optional<String> operatorChanged(Mutant mutant, Advice advice) {
    if (advice.before().isEmpty() || advice.after().isEmpty()) {
      return reversed(mutant);
    }
    var operands = mutant.operands();
    var before = advice.before().get();
    var after = advice.after().get();
    var exact =
        operands.size() == 2
            ? onceInCode("%s %s %s".formatted(operands.get(0), before, operands.get(1)))
                .map(at -> replaced(at + operands.get(0).length() + 1, before.length(), after))
            : Optional.<String>empty();
    return exact
        .or(
            () ->
                onceInWhole(" " + before + " ").map(at -> replaced(at + 1, before.length(), after)))
        .or(() -> compound(mutant, before, after));
  }

  /// `total += price` as `total -= price`, for a calculation the source writes that way.
  private Optional<String> compound(Mutant mutant, String before, String after) {
    if (!List.of(
            BuiltIn.MATH, BuiltIn.ARITHMETIC_REPLACEMENT, BuiltIn.OTHER_ARITHMETIC, BuiltIn.BITWISE)
        .contains(mutant.operator())) {
      return Optional.empty();
    }
    return onceInWhole(" " + before + "= ").map(at -> replaced(at + 1, before.length(), after));
  }

  /// A test of a `boolean` reversed: `done` as `!done`, `!done` as `done`.
  private Optional<String> reversed(Mutant mutant) {
    if (mutant.operator() != BuiltIn.COMPARISON
        && mutant.operator() != BuiltIn.OTHER_COMPARISON
        && mutant.operator() != BuiltIn.BOUNDARY) {
      return Optional.empty();
    }
    if (mutant.operands().size() != 1) {
      return Optional.empty();
    }
    var condition = mutant.operands().getFirst();
    var negated = onceInCode("!" + condition);
    if (negated.isPresent()) {
      return negated.map(at -> replaced(at, 1, ""));
    }
    return onceInCode(condition).map(at -> replaced(at, 0, "!"));
  }

  /// `return balance > 0;` as `return true;`: the condition, once in the statement, as the value it
  /// now always has.
  private Optional<String> conditionReplaced(Advice advice) {
    var always = ALWAYS.matcher(advice.change());
    if (!always.matches()) {
      return Optional.empty();
    }
    var condition = always.group("condition");
    var value = always.group("value");
    return onceInCode(condition)
        .map(at -> replaced(at, condition.length(), value))
        .or(() -> namedConstant(condition).map(found -> replaced(found, value)));
  }

  /// A comparison with a number javac wrote in where the source names a constant: `count >= 50`
  /// written `count >= BULK_FROM`. javac writes a name's value in only for a constant, so a name in
  /// the number's place, once in the statement, is that constant.
  private Optional<Span> namedConstant(String condition) {
    var parts = condition.split(" ", -1);
    if (parts.length != 3 || !(isNumber(parts[0]) ^ isNumber(parts[2]))) {
      return Optional.empty();
    }
    var name = "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)";
    var left = isNumber(parts[0]) ? name : Pattern.quote(parts[0]);
    var right = isNumber(parts[2]) ? name : Pattern.quote(parts[2]);
    var found =
        Pattern.compile(
                "(?<![\\w$.])"
                    + left
                    + "\\s*"
                    + Pattern.quote(parts[1])
                    + "\\s*"
                    + right
                    + "(?![\\w$(])")
            .matcher(Code.masked(text));
    if (!found.find(from)) {
      return Optional.empty();
    }
    var span = new Span(found.start(), found.end());
    return found.find() ? Optional.empty() : Optional.of(span);
  }

  private static boolean isNumber(String text) {
    return text.matches("-?\\d+");
  }

  /// Part of the statement, `from` to `to`.
  private record Span(int from, int to) {}

  private String replaced(Span span, String replacement) {
    return text.substring(0, span.from()) + replacement + text.substring(span.to());
  }

  /// `!true` as `false` and `!false` as `true`, the rest as written.
  static String simplified(String text) {
    var negated = NOT_A_VALUE.matcher(text);
    var out = new StringBuilder();
    while (negated.find()) {
      var value =
          negated.group("bare") != null ? negated.group("bare") : negated.group("bracketed");
      negated.appendReplacement(out, value.equals("true") ? "false" : "true");
    }
    negated.appendTail(out);
    return out.toString();
  }

  /// A `return` statement as one that returns the mutant's value.
  private Optional<String> returned(Advice advice) {
    var after = advice.after().filter(value -> value.startsWith("return "));
    var code = Code.masked(text);
    int at = code.indexOf("return ");
    var end = code.stripTrailing();
    boolean whole =
        at >= 0
            && code.substring(0, at).isBlank()
            && code.indexOf("return ", at + 1) < 0
            && (end.endsWith(";") || kotlin);
    if (after.isEmpty() || !whole) {
      return Optional.empty();
    }
    var value = end.endsWith(";") ? after.get() : after.get().replaceAll(";$", "");
    return Optional.of(text.substring(0, at) + value);
  }

  /// An assignment taken out: the statement gone when what's assigned changes nothing, or what's
  /// assigned left as a statement of its own when that's a call.
  private Optional<String> storeRemoved(Mutant mutant) {
    var label = mutant.replacement();
    var name = label.substring(label.lastIndexOf(' ') + 1);
    var code = Code.masked(text);
    var assignment =
        Pattern.compile(
                "^\\s*(?:[\\w$.]+\\.)?%s\\s*(?:[-+*/%%&|^]|<<|>>>?)?=(?!=)"
                    .formatted(Pattern.quote(name)))
            .matcher(code);
    var end = code.stripTrailing();
    if (!assignment.find() || !(end.endsWith(";") || kotlin)) {
      return Optional.empty();
    }
    var value =
        text.substring(assignment.end(), end.endsWith(";") ? end.length() - 1 : end.length());
    return Code.doesSomething(value) ? leftAlone(value) : Optional.of("");
  }

  /// What was assigned, as a statement of its own, when it can be one: it still runs.
  private Optional<String> leftAlone(String value) {
    var indent = text.substring(0, text.length() - text.stripLeading().length());
    return Code.asStatement(value).map(statement -> indent + statement + (kotlin ? "" : ";"));
  }

  /// `i++` as `i--`, `i += 2` as `i -= 2`, and the other way round. The label is the step the
  /// mutant takes, `i by -1`.
  private Optional<String> flipped(Mutant mutant) {
    var name = Pattern.quote(mutant.replacement().split(" ", 2)[0]);
    var steps =
        Pattern.compile(
            "(?<![\\w$.])(?:%s\\s*(?<after>\\+\\+|--|\\+=|-=)|(?<before>\\+\\+|--)\\s*%s(?![\\w$]))"
                .formatted(name, name));
    var found = steps.matcher(Code.masked(text));
    if (!found.find(from)) {
      return Optional.empty();
    }
    var group = found.group("after") != null ? "after" : "before";
    int at = found.start(group);
    var step = found.group(group);
    if (found.find()) {
      return Optional.empty();
    }
    var other = step.charAt(0) == '+' ? step.replace('+', '-') : step.replace('-', '+');
    return Optional.of(replaced(at, step.length(), other));
  }

  /// `-x` as `x`, when the statement negates once: a minus in front of a number is the number's.
  private Optional<String> negationRemoved() {
    var found = UNARY_MINUS.matcher(Code.masked(text));
    if (!found.find(from)) {
      return Optional.empty();
    }
    int at = found.start("minus");
    return found.find() ? Optional.empty() : Optional.of(replaced(at, 1, ""));
  }

  /// `a * b` as `a`, or `b`, when it's in the statement once.
  private Optional<String> operandKept(Mutant mutant) {
    var operands = mutant.operands();
    if (operands.size() != 2) {
      return Optional.empty();
    }
    var symbol = Advice.arithmetic(mutant.original());
    var whole = "%s %s %s".formatted(operands.get(0), symbol, operands.get(1));
    boolean first = mutant.replacement().endsWith("first");
    var kept = operands.get(first ? 0 : 1);
    if (Code.doesSomething(operands.get(first ? 1 : 0))) {
      return Optional.empty();
    }
    return onceInCode(whole).map(at -> replaced(at, whole.length(), kept));
  }

  /// A string or a regular expression changed: the label is `string "a" to ""`, each as the source
  /// writes it.
  private Optional<String> literalChanged(Mutant mutant) {
    var label = mutant.replacement();
    int to = label.lastIndexOf("\" to \"");
    if (to < 0) {
      return Optional.empty();
    }
    var before = label.substring(label.indexOf('"'), to + 1);
    var after = label.substring(to + "\" to ".length());
    int first = text.indexOf(before, from);
    return first < 0 || text.indexOf(before, first + 1) >= 0
        ? Optional.empty()
        : Optional.of(replaced(first, before.length(), after));
  }

  /// The `throw` gone, when it's the whole statement.
  private Optional<String> throwRemoved() {
    var code = Code.masked(text).strip();
    return code.startsWith("throw ") && code.endsWith(";") && code.indexOf(';') == code.length() - 1
        ? Optional.of("")
        : Optional.empty();
  }

  /// An arm's `case` gone, when it's one value with a line of its own: `case 2:`, or `case 2 ->
  /// "two";`. Not when javac's default would throw, which the source doesn't say.
  private Optional<String> armRemoved(Mutant mutant) {
    var code = Code.masked(text).strip();
    boolean single =
        !mutant.replacement().startsWith("throwing ")
            && code.startsWith("case ")
            && !code.contains(",")
            && (code.endsWith(":") || (code.contains("->") && code.endsWith(";")));
    return single ? Optional.of("") : Optional.empty();
  }

  /// `catch (A | B e)` without `B`.
  private Optional<String> catchTypeDropped(Mutant mutant) {
    var label = mutant.replacement();
    var simple = Pattern.quote(label.substring(label.lastIndexOf('/') + 1).replace('$', '.'));
    var types =
        Pattern.compile(
            "\\s*\\|\\s*(?:[\\w$]+\\.)*%s(?![\\w$])|(?<![\\w$.])(?:[\\w$]+\\.)*%s\\s*\\|\\s*"
                .formatted(simple, simple));
    var found = types.matcher(Code.masked(text));
    if (!found.find()) {
      return Optional.empty();
    }
    int at = found.start();
    int length = found.end() - at;
    return found.find() ? Optional.empty() : Optional.of(replaced(at, length, ""));
  }

  /// Where `found` is in the statement's code from javac's line, when it's there once as whole
  /// words: not run on into a name, a literal's suffix or a member (`1` in `1L`, `day` in `days`).
  private Optional<Integer> onceInCode(String found) {
    return once(found, from);
  }

  /// Where `found` is in the statement's code, when the whole statement has it once.
  private Optional<Integer> onceInWhole(String found) {
    return once(found, 0);
  }

  private Optional<Integer> once(String found, int start) {
    var whole =
        Pattern.compile(
            (startsAWord(found.charAt(0)) ? "(?<![\\w.$])" : "")
                + Pattern.quote(found)
                + (startsAWord(found.charAt(found.length() - 1)) ? "(?![\\w.$])" : ""));
    // A string javac wrote in is found as the source writes it, so search the text, not its code,
    // but only where the code is: a match inside a string or a comment doesn't count.
    var code = Code.masked(text);
    Matcher at = whole.matcher(text);
    Optional<Integer> first = Optional.empty();
    for (int searched = start; at.find(searched); searched = at.start() + 1) {
      if (inCode(code, at.start(), found)) {
        if (first.isPresent()) {
          return Optional.empty();
        }
        first = Optional.of(at.start());
      }
    }
    return first;
  }

  /// Whether a match starts in code: as text, its first character isn't covered over, or it's a
  /// whole string the operands quote.
  private static boolean inCode(String code, int at, String found) {
    return code.charAt(at) == found.charAt(0) || found.charAt(0) == '"' || found.charAt(0) == '\'';
  }

  private static boolean startsAWord(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '$';
  }

  private String replaced(int at, int length, String replacement) {
    return text.substring(0, at) + replacement + text.substring(at + length);
  }
}
