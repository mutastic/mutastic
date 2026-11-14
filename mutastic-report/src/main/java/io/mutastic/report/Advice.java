package io.mutastic.report;

import static java.util.Objects.requireNonNull;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.ClassMutator;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Mutator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/// What a mutant changed and what test would catch it, in source terms, one template per operator,
/// filled from what the run recorded: the values compared (`total`, `100`) and the inputs the tests
/// passed.
///
/// @param before the source token the mutant changes, `>=`, when the bytecode says which
/// @param after what it becomes, `>`
/// @param change the change in words, for when the line can't be rewritten
/// @param changedTo the change as the label under it in a code frame: `>= changed to >`
/// @param headline the gap in the tests, for a missed mutant
/// @param notes the evidence: what the tests did
/// @param fix the test to write
/// @param original the source the mutant replaces, as the events and reports give it: the token, or
///   for a condition made constant the condition, `total >= 100`, where the run says which
/// @param replacement the source that replaces it: `>`, `false`, or a return's new value, `0`
public record Advice(
    Optional<String> before,
    Optional<String> after,
    String change,
    String changedTo,
    String headline,
    List<String> notes,
    String fix,
    Optional<String> original,
    Optional<String> replacement) {

  public Advice {
    notes = List.copyOf(notes);
  }

  /// Advice whose change, as source, is its token before and after: what most operators change.
  public Advice(
      Optional<String> before,
      Optional<String> after,
      String change,
      String changedTo,
      String headline,
      List<String> notes,
      String fix) {
    this(before, after, change, changedTo, headline, notes, fix, before, after);
  }

  /// The same advice with other notes.
  Advice withNotes(List<String> others) {
    return new Advice(
        before, after, change, changedTo, headline, others, fix, original, replacement);
  }

  /// javac usually compiles an `if` to a jump past its body, so the jump tests the opposite of the
  /// source: `if (total >= 100)` becomes `IF_ICMPLT`, "jump if less", and the source operator is
  /// the negation of the jump's. Loops, `?:` and `return a < b` compile the same way. Not always:
  /// in `a < 0 || b`, the first test jumps *into* the body, so it reads as written. [SourceView]
  /// settles which by looking for both in the statement.
  private static final Map<String, String> JUMP_TO_SOURCE =
      Map.of(
          "EQ", "!=", "NE", "==", "LT", ">=", "GE", "<", "GT", "<=", "LE", ">", "NULL", "!=",
          "NONNULL", "==");

  /// The jump's condition as written, for a jump that isn't inverted.
  private static final Map<String, String> JUMP_AS_WRITTEN =
      Map.of(
          "EQ", "==", "NE", "!=", "LT", "<", "GE", ">=", "GT", ">", "LE", "<=", "NULL", "==",
          "NONNULL", "!=");

  private static final Map<String, String> ARITHMETIC =
      Map.ofEntries(
          Map.entry("ADD", "+"),
          Map.entry("SUB", "-"),
          Map.entry("MUL", "*"),
          Map.entry("DIV", "/"),
          Map.entry("REM", "%"),
          Map.entry("AND", "&"),
          Map.entry("OR", "|"),
          Map.entry("XOR", "^"),
          Map.entry("SHL", "<<"),
          Map.entry("SHR", ">>"),
          Map.entry("USHR", ">>>"));

  private static final Pattern NUMBER = Pattern.compile("-?\\d+");

  /// A character as an operand writes it: `'/'`, `'\\n'`, `'\\''`.
  private static final Pattern CHARACTER = Pattern.compile("'(?<character>[^'\\\\]|\\\\.)'");

  /// The advice with nothing but the mutant to go on, reading a jump as javac usually writes it.
  public static Advice of(Mutant mutant) {
    return of(mutant, Map.of(), false);
  }

  /// The advice with the inputs the covering tests passed.
  public static Advice of(MutantResult result) {
    return of(result.mutant(), result.inputs(), false);
  }

  /// The advice, its jump read as written in the source or inverted as javac usually compiles it.
  ///
  /// @param asWritten the jump tests the source's own condition, not its opposite
  static Advice of(Mutant mutant, Map<String, List<Long>> values, boolean asWritten) {
    var seen = Inputs.of(mutant, values);
    boolean written = jumpsWhenTrue(mutant).orElse(asWritten);
    return switch (mutant.operator()) {
      case Mutator mutator -> added(mutator.words(mutant), seen);
      case ClassMutator mutator -> added(mutator.words(mutant), seen);
      case BuiltIn builtIn -> ofBuiltIn(builtIn, mutant, seen, written);
    };
  }

  private static Advice ofBuiltIn(BuiltIn builtIn, Mutant mutant, Inputs seen, boolean asWritten) {
    return switch (builtIn) {
      case BOUNDARY, COMPARISON, OTHER_COMPARISON -> comparison(mutant, seen, asWritten);
      case MATH, ARITHMETIC_REPLACEMENT, OTHER_ARITHMETIC, BITWISE -> math(mutant, seen);
      case KEEP_OPERAND -> keptOperand(mutant, seen);
      case RETURN_VALUE -> returnValue(mutant, seen);
      case REMOVE_VOID_CALL, REMOVE_DISCARDED_CALL -> removeVoidCall(mutant, seen);
      case REMOVE_CONDITIONAL -> removeConditional(mutant, seen, asWritten);
      case LOGICAL_CONNECTOR -> connector(mutant, seen, Optional.empty());
      case REMOVE_ASSIGNMENT -> removedStore(mutant, seen, "assignment");
      case REMOVE_FIELD_WRITE -> removedStore(mutant, seen, "write");
      case OMIT_CALL -> omittedCall(mutant, seen);
      case SWAP_ARGUMENTS -> swappedArguments(mutant, seen);
      case NULL_PARAMETER -> nullParameter(mutant, seen);
      case DROP_VARARG -> droppedElement(mutant, seen);
      case FLIP_INCREMENT -> flippedIncrement(mutant, seen);
      case CONSTANT_VALUE -> constant(mutant, seen, UnaryOperator.identity());
      case REMOVE_NEGATION -> removedNegation(mutant, seen);
      case REMOVE_SWITCH_ARM, REPLACE_BODY, DROP_CATCH_TYPE, REMOVE_THROW ->
          wholeCode(builtIn, mutant, seen);
      case STRING_CONSTANT -> changedString(mutant, seen);
      case REGEX_CONSTANT -> changedRegex(mutant, seen);
    };
  }

  /// The operators that change more of the code than one instruction's value.
  private static Advice wholeCode(BuiltIn builtIn, Mutant mutant, Inputs seen) {
    return switch (builtIn) {
      case REPLACE_BODY -> replacedBody(mutant, seen);
      case DROP_CATCH_TYPE -> droppedCatch(mutant, seen);
      case REMOVE_THROW -> removedThrow(mutant, seen);
      default -> switchArm(mutant, seen, UnaryOperator.identity());
    };
  }

  /// Whether the operator changes a conditional jump, so its card reads the jump as the source has
  /// it.
  static boolean onJump(io.mutastic.api.Operator operator) {
    return operator == BuiltIn.BOUNDARY
        || operator == BuiltIn.COMPARISON
        || operator == BuiltIn.OTHER_COMPARISON
        || operator == BuiltIn.REMOVE_CONDITIONAL;
  }

  /// Whether the mutant turns a condition round, its jump to the opposite one. Which way javac
  /// compiled the jump doesn't matter: the opposites of two comparisons disagree where they do.
  static boolean reverses(Mutant mutant) {
    return comparisonChange(mutant).filter(differ -> differ.equals(Sides.ALL)).isPresent();
  }

  /// Where a comparison's mutant disagrees with it, for a jump on two values or one and zero.
  private static Optional<Sides> comparisonChange(Mutant mutant) {
    if (!onJump(mutant.operator()) || mutant.operator() == BuiltIn.REMOVE_CONDITIONAL) {
      return Optional.empty();
    }
    var before = token(mutant.original(), false);
    var after = token(mutant.replacement(), false);
    return before.isPresent() && after.isPresent()
        ? Optional.of(Sides.of(before.get()).differing(Sides.of(after.get())))
        : Optional.empty();
  }

  /// A connector swapped, as the source has it: `&&` and its two sides, when the source says.
  ///
  /// @param connector the connector the statement has, and its sides when they're on one line
  record Connective(String connector, Optional<String> left, Optional<String> right) {
    String other() {
      return connector.equals("&&") ? "||" : "&&";
    }
  }

  /// `&&` becomes `||`, or the other way round: they differ exactly where one side holds and the
  /// other doesn't, so that's the test to add.
  static Advice connector(Mutant mutant, Inputs seen, Optional<Connective> connective) {
    var method = method(mutant);
    if (connective.isEmpty()) {
      return unnamedConnector(method, seen);
    }
    var known = connective.get();
    var sides =
        known.left().isPresent() && known.right().isPresent()
            ? "one of `%s` and `%s`".formatted(known.left().get(), known.right().get())
            : "one side of the `%s`".formatted(known.connector());
    return new Advice(
        Optional.of(known.connector()),
        Optional.of(known.other()),
        "`%s` becomes `%s`".formatted(known.connector(), known.other()),
        known.connector() + " changed to " + known.other(),
        "no test checks %s with %s true and the other false".formatted(method, sides),
        inputsSeen(seen),
        "add a test where %s is true and the other false, and assert what %s does then"
            .formatted(sides, method));
  }

  /// A connector the source doesn't say: the statement has more than one, or isn't there.
  private static Advice unnamedConnector(String method, Inputs seen) {
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "a condition's `&&` becomes `||`, or its `||` `&&`",
        "&& and || swapped",
        "no test checks %s with one side of a condition true and the other false".formatted(method),
        inputsSeen(seen),
        "add a test where one side of the condition is true and the other false, and assert the"
            + " result");
  }

  /// A plugin's or rules file's operator: its own words for the card.
  private static Advice added(Mutator.Words words, Inputs seen) {
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        words.changed(),
        words.changed(),
        words.headline(),
        inputsSeen(seen),
        words.help());
  }

  /// The condition no longer decides: always true or always false, by which way the jump now goes
  /// and which way javac compiled it.
  private static Advice removeConditional(Mutant mutant, Inputs seen, boolean asWritten) {
    var method = method(mutant);
    var token = jump(mutant.original(), mutant.operands(), asWritten);
    var condition = condition(mutant.operands(), token);
    boolean always = mutant.replacement().equals("always jump");
    // javac's usual jump skips the body when the condition is false: always taken, the body never
    // runs, as if the condition were always false. A jump as written is the other way round.
    boolean forced = always == asWritten;
    var truth = forced ? "true" : "false";
    var other = forced ? "false" : "true";
    return new Advice(
        token,
        Optional.empty(),
        "%s is always %s".formatted(condition, truth),
        "now always " + truth,
        "no test needs %s to be %s in %s".formatted(condition, other, method),
        inputsSeen(seen),
        "add a test where %s is %s, and assert what %s does then"
            .formatted(condition, other, method),
        conditionSource(mutant.operands(), token),
        Optional.of(truth));
  }

  /// A comparison changed, by where the two comparisons give different answers: only where its two
  /// sides are equal, its edge moved (`<` to `<=`), and the advice is the boundary's; everywhere,
  /// the condition reversed; otherwise on one side of the edge (`<` to `!=` disagree where `a >
  /// b`), and the advice names that side.
  private static Advice comparison(Mutant mutant, Inputs seen, boolean asWritten) {
    // A jump on a boolean has no operator to show, but one that's ordered (`IFLT`) compares a
    // number with zero.
    boolean numbers =
        mutant.operands().size() == 2
            || ordered(mutant.original())
            || ordered(mutant.replacement());
    var before = numbers ? token(mutant.original(), asWritten) : Optional.<String>empty();
    var after = numbers ? token(mutant.replacement(), asWritten) : Optional.<String>empty();
    if (before.isEmpty() || after.isEmpty()) {
      return negateConditional(mutant, seen, asWritten);
    }
    var differ = Sides.of(before.get()).differing(Sides.of(after.get()));
    if (differ.equals(Sides.EQUAL)) {
      return boundary(mutant, seen, before.get(), after.get());
    }
    if (differ.equals(Sides.ALL)) {
      return negateConditional(mutant, seen, asWritten);
    }
    return sided(mutant, seen, new Change(before.get(), after.get(), differ));
  }

  /// A comparison changed to one that gives a different answer on one side of its edge: `before`,
  /// `after`, and the sides where they disagree.
  private record Change(String before, String after, Sides differ) {}

  /// `<` to `!=`: they disagree where `a > b`, so the test to add has `a > b`.
  private static Advice sided(Mutant mutant, Inputs seen, Change change) {
    var method = method(mutant);
    var operands = mutant.operands();
    var where = change.differ().token();
    var headline =
        operands.size() == 2
            ? "no test checks %s with `%s %s %s`"
                .formatted(method, operands.get(0), where, operands.get(1))
            : "no test tells `%s` from `%s` in %s"
                .formatted(change.before(), change.after(), method);
    var fix =
        operands.size() == 2
            ? "add a test where `%s %s %s`, and assert what %s does then"
                .formatted(operands.get(0), where, operands.get(1), method)
            : "add a test where `%s` and `%s` give different answers, and assert the result"
                .formatted(change.before(), change.after());
    return new Advice(
        Optional.of(change.before()),
        Optional.of(change.after()),
        "`%s` becomes `%s`".formatted(change.before(), change.after()),
        change.before() + " changed to " + change.after(),
        headline,
        sidedNotes(method, operands, change.differ(), seen),
        fix);
  }

  /// That no test's input is on the side where the two disagree, when the inputs show it: the name
  /// compared with a number, and every value it was seen with on the other side.
  private static List<String> sidedNotes(
      String method, List<String> operands, Sides differ, Inputs seen) {
    var comparison = Comparison.of(operands);
    if (comparison.isEmpty()
        || number(comparison.get().value()).isEmpty()
        || !seen.values().containsKey(comparison.get().name())) {
      return inputsSeen(seen);
    }
    long value = number(comparison.get().value()).getAsLong();
    // The name's side of the comparison, as the source has it: `100 < total` has `total` second.
    boolean nameFirst = operands.get(0).equals(comparison.get().name());
    var values = seen.values().get(comparison.get().name());
    boolean noneThere =
        values.stream()
            .noneMatch(
                input ->
                    differ.has(
                        nameFirst ? Long.compare(input, value) : Long.compare(value, input)));
    if (values.isEmpty() || !noneThere) {
      return inputsSeen(seen);
    }
    var other = differ.others().token();
    return List.of(
        "every test calls %s with `%s %s %s`"
            .formatted(method, operands.get(0), other, operands.get(1)),
        "inputs seen: %s".formatted(comparison.get().join(values)));
  }

  /// The number an operand writes: `100`, or `47` for `'/'`; empty for anything else.
  static java.util.OptionalLong number(String operand) {
    if (NUMBER.matcher(operand).matches()) {
      return java.util.OptionalLong.of(Long.parseLong(operand));
    }
    var character = CHARACTER.matcher(operand);
    if (!character.matches()) {
      return java.util.OptionalLong.empty();
    }
    var written = character.group("character");
    return java.util.OptionalLong.of(
        written.length() == 1 ? written.charAt(0) : unescaped(written.charAt(1)));
  }

  /// The character an escape, `\\n` and the like, stands for.
  private static char unescaped(char escape) {
    return switch (escape) {
      case 'n' -> '\n';
      case 't' -> '\t';
      case 'r' -> '\r';
      case 'b' -> '\b';
      case 'f' -> '\f';
      case '0' -> '\0';
      default -> escape;
    };
  }

  /// The signs of `a - b` a comparison holds for, from below to above: `<=` holds below and at
  /// equal.
  private record Sides(boolean below, boolean equal, boolean above) {
    static final Sides EQUAL = new Sides(false, true, false);
    static final Sides ALL = new Sides(true, true, true);

    static Sides of(String token) {
      return switch (token) {
        case "<" -> new Sides(true, false, false);
        case "<=" -> new Sides(true, true, false);
        case ">" -> new Sides(false, false, true);
        case ">=" -> new Sides(false, true, true);
        case "==" -> EQUAL;
        default -> new Sides(true, false, true); // !=
      };
    }

    Sides differing(Sides other) {
      return new Sides(below != other.below, equal != other.equal, above != other.above);
    }

    Sides others() {
      return new Sides(!below, !equal, !above);
    }

    /// Whether it holds where `a - b` has the sign `sign`.
    boolean has(int sign) {
      return sign < 0 ? below : sign == 0 ? equal : above;
    }

    /// The comparison that holds on these sides: `>` for above only.
    String token() {
      return List.of("<", "<=", ">", ">=", "==", "!=").stream()
          .filter(token -> of(token).equals(this))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("no comparison holds on " + this));
    }
  }

  /// A jump that orders what it compares: a comparison of numbers, never of `boolean`s.
  private static boolean ordered(String opcode) {
    return opcode.endsWith("LT")
        || opcode.endsWith("LE")
        || opcode.endsWith("GT")
        || opcode.endsWith("GE");
  }

  /// The edge of the comparison moved: `<` to `<=`.
  private static Advice boundary(Mutant mutant, Inputs seen, String before, String after) {
    var method = method(mutant);
    var comparison = Comparison.of(mutant.operands());
    return new Advice(
        Optional.of(before),
        Optional.of(after),
        "`%s` becomes `%s`".formatted(before, after),
        before + " changed to " + after,
        boundaryHeadline(method, comparison, before),
        boundaryNotes(method, comparison, seen),
        boundaryFix(method, comparison, before, after));
  }

  /// That every test stays away from the boundary, when the inputs show it; else the inputs seen.
  private static List<String> boundaryNotes(
      String method, Optional<Comparison> comparison, Inputs seen) {
    return comparison
        .flatMap(
            found ->
                found
                    .boundary(seen)
                    .map(
                        values ->
                            List.of(
                                "every test calls %s away from the boundary".formatted(method),
                                "inputs seen: %s (none is %s)"
                                    .formatted(found.join(values), found.value()))))
        .orElseGet(() -> inputsSeen(seen));
  }

  private static String boundaryHeadline(
      String method, Optional<Comparison> comparison, String before) {
    return comparison
        .map(
            found ->
                "no test checks %s at `%s == %s`".formatted(method, found.name(), found.value()))
        .orElse("no test checks the boundary of `%s` in %s".formatted(before, method));
  }

  private static String boundaryFix(
      String method, Optional<Comparison> comparison, String before, String after) {
    return comparison
        .map(
            found ->
                "test %s with `%s = %s` and assert the result"
                    .formatted(method, found.name(), found.value()))
        .orElse(
            "add a test at the boundary value, where `%s` and `%s` disagree, and assert the result"
                .formatted(before, after));
  }

  private static Advice negateConditional(Mutant mutant, Inputs seen, boolean asWritten) {
    var method = method(mutant);
    var before = jump(mutant.original(), mutant.operands(), asWritten);
    var after = jump(mutant.replacement(), mutant.operands(), asWritten);
    var condition = condition(mutant.operands(), before);
    return new Advice(
        before,
        after,
        before.isPresent()
            ? "`%s` becomes `%s`".formatted(before.get(), after.get())
            : "the condition is reversed",
        before.map(token -> token + " changed to " + after.get()).orElse("reversed"),
        "%s behaves the same with %s reversed".formatted(capitalised(method), condition),
        inputsSeen(seen),
        "assert the result on both sides of " + condition);
  }

  private static Advice math(Mutant mutant, Inputs seen) {
    var method = method(mutant);
    var operands = mutant.operands();
    var before = arithmetic(mutant.original());
    var after = arithmetic(mutant.replacement());
    boolean known = operands.size() == 2;
    var original =
        known
            ? "`%s %s %s`".formatted(operands.get(0), before, operands.get(1))
            : "`" + before + "`";
    var swapped =
        known ? "`%s %s %s`".formatted(operands.get(0), after, operands.get(1)) : "`" + after + "`";
    return new Advice(
        Optional.of(before),
        Optional.of(after),
        "`%s` becomes `%s`".formatted(before, after),
        before + " changed to " + after,
        "tests pass even when `%s` becomes `%s` in %s".formatted(before, after, method),
        inputsSeen(seen),
        "use inputs where %s and %s differ, and assert the result".formatted(original, swapped));
  }

  /// A calculation down to one of its values: the test to add is one the other changes the result
  /// of. The label says which is kept, `keep first`.
  private static Advice keptOperand(Mutant mutant, Inputs seen) {
    var method = method(mutant);
    var symbol = arithmetic(mutant.original());
    boolean first = mutant.replacement().endsWith("first");
    var operands = mutant.operands();
    if (operands.size() != 2) {
      var lost = "the %s value of `%s`".formatted(first ? "second" : "first", symbol);
      return kept(
          "`%s` keeps only its %s value".formatted(symbol, first ? "first" : "second"),
          "no test needs %s in %s".formatted(lost, method),
          "add a test where %s changes the result, and assert it".formatted(lost),
          seen);
    }
    var whole = "`%s %s %s`".formatted(operands.get(0), symbol, operands.get(1));
    var left = "`%s`".formatted(operands.get(first ? 0 : 1));
    var lost = "`%s`".formatted(operands.get(first ? 1 : 0));
    return kept(
        "%s becomes %s".formatted(whole, left),
        "no test needs %s in %s in %s".formatted(lost, whole, method),
        "add a test where %s changes the result of %s, and assert it".formatted(lost, whole),
        seen);
  }

  private static Advice kept(String change, String headline, String fix, Inputs seen) {
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        change,
        "one value kept",
        headline,
        inputsSeen(seen),
        fix);
  }

  /// The return gives another value: the card says which, and asks for a test that needs the one it
  /// gives today. A `boolean`, or a number that's `0` today, names that value.
  private static Advice returnValue(Mutant mutant, Inputs seen) {
    var method = method(mutant);
    var written = returnedAsWritten(mutant);
    var number = mutant.replacement().substring("return ".length());
    var value = written.apply(number);
    var notes = new ArrayList<String>();
    notes.add("tests reach this return, but none fails when it returns `%s`".formatted(value));
    notes.addAll(inputsSeen(seen));
    var today = returnedToday(number).map(written);
    return new Advice(
        Optional.empty(),
        Optional.of("return " + value + ";"),
        "returns `%s` instead".formatted(value),
        "now returns " + value,
        today
            .map(was -> "no test checks %s returning `%s` here".formatted(method, was))
            .orElse("no test checks what %s returns here".formatted(method)),
        notes,
        returnFix(method, value, today),
        Optional.empty(),
        Optional.of(value));
  }

  /// The test that needs what the return gives today, when that's known.
  private static String returnFix(String method, String value, Optional<String> today) {
    return today
        .map(was -> "add a test where %s returns `%s` here, and assert it".formatted(method, was))
        .orElseGet(() -> returnHelp(method, value));
  }

  /// What a return gives today, when the mutant's value says: the other `boolean`, or the `0` a
  /// mutant that returns `1` took away. A value a `char` method returns as a character, `'\\0'`,
  /// where the bytecode has `0`.
  private static UnaryOperator<String> returnedAsWritten(Mutant mutant) {
    boolean character = mutant.descriptor().endsWith(")C");
    return value ->
        character && NUMBER.matcher(value).matches()
            ? Inputs.character(Long.parseLong(value))
            : value;
  }

  private static Optional<String> returnedToday(String value) {
    return switch (value) {
      case "true" -> Optional.of("false");
      case "false" -> Optional.of("true");
      default -> value.startsWith("1") ? Optional.of("0" + value.substring(1)) : Optional.empty();
    };
  }

  private static String returnHelp(String method, String value) {
    if (value.equals("null")) {
      return "assert the value %s returns on this path".formatted(method);
    }
    var other = value.startsWith("0") || value.startsWith("'") ? "`" + value + "`" : "empty";
    return "assert the value %s returns on this path, with inputs where it isn't %s"
        .formatted(method, other);
  }

  /// An assignment or a field write taken away: what's stored keeps the value it had.
  private static Advice removedStore(Mutant mutant, Inputs seen, String kind) {
    var change = mutant.replacement();
    var name = "`%s`".formatted(change.substring(change.lastIndexOf(' ') + 1));
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "the %s to %s is removed, so it keeps its earlier value".formatted(kind, name),
        kind + " removed",
        "no test checks the value %s is given in %s".formatted(name, method(mutant)),
        inputsSeen(seen),
        "add a test where this gives %s a different value, and assert what depends on it"
            .formatted(name));
  }

  /// A call left out: what it was called on, or given, goes on in its place.
  private static Advice omittedCall(Mutant mutant, Inputs seen) {
    var called = "`%s()`".formatted(SourceView.calledName(mutant.original()));
    var kept =
        mutant.replacement().endsWith("receiver") ? "what it's called on" : "what it's given";
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "%s is left out, so %s goes on in its place".formatted(called, kept),
        "call left out",
        "no test needs %s in %s".formatted(called, method(mutant)),
        inputsSeen(seen),
        "add a test with a value %s changes, and assert the result".formatted(called));
  }

  /// A call's last two arguments, the other way round.
  private static Advice swappedArguments(Mutant mutant, Inputs seen) {
    var called = "`%s()`".formatted(SourceView.calledName(mutant.original()));
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "the last two arguments of %s swap places".formatted(called),
        "arguments swapped",
        "no test notices the last two arguments of %s swapped in %s"
            .formatted(called, method(mutant)),
        inputsSeen(seen),
        "add a test that passes %s different values for them, and assert the result"
            .formatted(called));
  }

  /// A parameter that's `null` however the method is called.
  private static Advice nullParameter(Mutant mutant, Inputs seen) {
    var change = mutant.replacement();
    var name = "`%s`".formatted(change.substring(change.lastIndexOf(' ') + 1));
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "%s is `null` from the start of the method".formatted(name),
        "parameter is null",
        "no test depends on the value of %s in %s".formatted(name, method(mutant)),
        inputsSeen(seen),
        "add a test whose result depends on %s, and assert it".formatted(name));
  }

  /// The array built for a call, one element short.
  private static Advice droppedElement(Mutant mutant, Inputs seen) {
    var called = "`%s()`".formatted(SourceView.calledName(mutant.original()));
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "the last value in the array given to %s is left out".formatted(called),
        "last value left out",
        "no test needs the last value given to %s in %s".formatted(called, method(mutant)),
        inputsSeen(seen),
        "add a test whose result depends on the last value given to %s, and assert it"
            .formatted(called));
  }

  /// A number in the code changed. The label is `constant 100 to 101`. A `static final` is written
  /// where it's used, so the source may have a name instead: the card says the value.
  ///
  /// @param written a value as the source would write it: `'a'` for `97`, if that's what it has
  static Advice constant(Mutant mutant, Inputs seen, UnaryOperator<String> written) {
    var label = mutant.replacement().split(" ", 4);
    var today = written.apply(label[1]);
    var mutated = written.apply(label[3]);
    return new Advice(
        Optional.of(today),
        Optional.of(mutated),
        "the constant `%s` becomes `%s`".formatted(today, mutated),
        today + " changed to " + mutated,
        "no test tells `%s` from `%s` in %s".formatted(today, mutated, method(mutant)),
        inputsSeen(seen),
        "add a test near where `%s` takes effect, whose result would differ with `%s`, and assert"
                .formatted(today, mutated)
            + " it");
  }

  /// A switch's arm removed. The label is `arm 2 2`, the value in the bytecode and then how the
  /// source says it, if the bytecode shows that: an `enum`'s has no name, and the card is on the
  /// arm's own line. `throwing ` in front says the default is javac's, which throws.
  ///
  /// @param written a value as the source would write it: `'a'` for `97`, if that's what it has
  static Advice switchArm(Mutant mutant, Inputs seen, UnaryOperator<String> written) {
    var label = mutant.replacement();
    boolean throwing = label.startsWith("throwing ");
    var words = label.substring(label.indexOf("arm ") + "arm ".length()).split(" ", 2);
    var arm = words.length == 2 ? "`case %s`".formatted(written.apply(words[1])) : "this arm";
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        throwing
            ? "%s is removed, so a value that reached it throws".formatted(arm)
            : "%s is removed, so its values go to the default".formatted(arm),
        throwing ? "arm removed, now throws" : "arm removed",
        "no test needs %s in %s".formatted(arm, method(mutant)),
        inputsSeen(seen),
        "add a test with a value that reaches %s, and assert what it does".formatted(arm));
  }

  /// A whole method's code gone. The label is `body return 0`, or `body emptied` for a method that
  /// returns nothing.
  private static Advice replacedBody(Mutant mutant, Inputs seen) {
    var method = method(mutant);
    var label = mutant.replacement();
    if (!label.startsWith("body return ")) {
      return new Advice(
          Optional.empty(),
          Optional.empty(),
          "%s does nothing".formatted(capitalised(method)),
          "body removed",
          "no test notices %s doing nothing".formatted(method),
          inputsSeen(seen),
          "add a test that calls %s and asserts what it does".formatted(method));
    }
    var value = label.substring("body return ".length());
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "%s does nothing but return `%s`".formatted(capitalised(method), value),
        "now only returns " + value,
        "no test notices %s doing nothing but returning `%s`".formatted(method, value),
        inputsSeen(seen),
        "add a test that calls %s and asserts a result other than `%s`".formatted(method, value));
  }

  /// One type of a multi-catch gone. The label ends in the type's internal name.
  private static Advice droppedCatch(Mutant mutant, Inputs seen) {
    var label = mutant.replacement();
    var type = "`%s`".formatted(label.substring(label.lastIndexOf('/') + 1));
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "this `catch` no longer catches %s".formatted(type),
        "no longer catches it",
        "no test has %s reach this `catch` in %s".formatted(type, method(mutant)),
        inputsSeen(seen),
        "add a test where the `try` throws %s, and assert what the `catch` does".formatted(type));
  }

  /// A `throw` gone. The label is `remove throw X`, with what's thrown if the code just before it
  /// says: a type, or a variable.
  private static Advice removedThrow(Mutant mutant, Inputs seen) {
    var label = mutant.replacement();
    var named = label.length() > "remove throw".length();
    var what = named ? "`%s`".formatted(label.substring(label.lastIndexOf(' ') + 1)) : "this";
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        named
            ? "the `throw` of %s is removed, so the code carries on".formatted(what)
            : "this `throw` is removed, so the code carries on",
        "throw removed",
        "no test needs %s to throw %s here".formatted(method(mutant), what),
        inputsSeen(seen),
        named
            ? "add a test where this throws %s, and assert that it does".formatted(what)
            : "add a test where this throws, and assert that it does");
  }

  /// A string changed. The label is `string "a" to ""`.
  private static Advice changedString(Mutant mutant, Inputs seen) {
    var label = mutant.replacement();
    int to = label.lastIndexOf(" to ");
    var today = label.substring("string ".length(), to);
    var mutated = label.substring(to + " to ".length());
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "the string `%s` becomes `%s`".formatted(today, mutated),
        today + " changed to " + mutated,
        "no test tells `%s` from `%s` in %s".formatted(today, mutated, method(mutant)),
        inputsSeen(seen),
        "add a test whose result depends on `%s`, and assert it".formatted(today));
  }

  /// A regular expression changed. The label is `regex "^a" to "a"`, with each as a string in the
  /// source would be written. The card shows them as they mean, and in a field says so: its method
  /// is javac's.
  private static Advice changedRegex(Mutant mutant, Inputs seen) {
    var label = mutant.replacement();
    int to = label.lastIndexOf("\" to \"");
    var today = label.substring("regex \"".length(), to).replace("\\\\", "\\");
    var mutated =
        label.substring(to + "\" to \"".length(), label.length() - 1).replace("\\\\", "\\");
    var where =
        switch (mutant.methodName()) {
          case "<clinit>" -> "a static field or initialiser";
          case "<init>" -> "a field or constructor";
          default -> method(mutant);
        };
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "the regular expression `%s` becomes `%s`".formatted(today, mutated),
        today + " changed to " + mutated,
        "no test tells `%s` from `%s` in %s".formatted(today, mutated, where),
        inputsSeen(seen),
        "add a test with text only one of `%s` and `%s` matches, and assert it"
            .formatted(today, mutated));
  }

  /// A variable's step the other way. The label is the step the mutant takes, `i by -1`.
  private static Advice flippedIncrement(Mutant mutant, Inputs seen) {
    var label = mutant.replacement().split(" ", 3);
    var name = "`%s`".formatted(label[0]);
    int step = Integer.parseInt(label[2]);
    var today = step < 0 ? "up" : "down";
    var mutated = step < 0 ? "down" : "up";
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "%s goes %s by %d instead of %s".formatted(name, mutated, Math.abs(step), today),
        "now goes " + mutated,
        "no test needs %s to go %s in %s".formatted(name, today, method(mutant)),
        inputsSeen(seen),
        "add a test whose result depends on %s going %s here, and assert it"
            .formatted(name, today));
  }

  /// A value without its minus sign.
  private static Advice removedNegation(Mutant mutant, Inputs seen) {
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "a minus sign is removed, so the value keeps its sign",
        "minus sign removed",
        "no test checks the sign of the negated value in %s".formatted(method(mutant)),
        inputsSeen(seen),
        "add a test with a value that isn't zero, and assert the sign of the result");
  }

  private static Advice removeVoidCall(Mutant mutant, Inputs seen) {
    if (mutant.original().contains("kotlin/jvm/internal/Intrinsics.checkNotNull(")) {
      return kotlinNullCheck(mutant, seen);
    }
    var called = "`%s()`".formatted(SourceView.calledName(mutant.original()));
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "the call to %s is removed".formatted(called),
        "call removed",
        "no test checks the effect of calling %s in %s".formatted(called, method(mutant)),
        inputsSeen(seen),
        "assert what %s does: the state it changes or the call it makes".formatted(called));
  }

  /// A Kotlin `x!!`, or with a message `x as String`: the check that's the source's, not kotlinc's.
  private static Advice kotlinNullCheck(Mutant mutant, Inputs seen) {
    var written = mutant.original().contains("(Ljava/lang/Object;)V") ? "`!!`" : "`as`";
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "the %s lets a `null` through".formatted(written),
        "lets a `null` through",
        "no test has a `null` reach the %s in %s".formatted(written, method(mutant)),
        inputsSeen(seen),
        "add a test where the value is `null`, and assert it throws a `NullPointerException`");
  }

  /// The same advice, its words changed: for what the source says where the bytecode says another
  /// thing.
  Advice reworded(UnaryOperator<String> words) {
    return new Advice(
        before,
        after,
        words.apply(change),
        words.apply(changedTo),
        words.apply(headline),
        notes.stream().map(words).toList(),
        words.apply(fix),
        original,
        replacement);
  }

  /// A comparison of a name with a value, the name first however the source has it: `total >= 100`
  /// and `100 <= total` both give `total` and `100`.
  private record Comparison(String name, String value) {
    static Optional<Comparison> of(List<String> operands) {
      if (operands.size() != 2) {
        return Optional.empty();
      }
      boolean leftNumber = number(operands.get(0)).isPresent();
      boolean rightNumber = number(operands.get(1)).isPresent();
      if (leftNumber && !rightNumber) {
        return Optional.of(new Comparison(operands.get(1), operands.get(0)));
      }
      return Optional.of(new Comparison(operands.get(0), operands.get(1)));
    }

    /// The inputs the tests passed for the name, when it's a parameter compared with a number and
    /// none of them is that number.
    Optional<List<Long>> boundary(Inputs seen) {
      var values = seen.values().get(name);
      var number = number(value);
      if (values == null || values.isEmpty() || number.isEmpty()) {
        return Optional.empty();
      }
      return values.contains(number.getAsLong()) ? Optional.empty() : Optional.of(values);
    }

    /// The values seen, as the value's written: characters, `'a', 'z'`, for a character.
    String join(List<Long> values) {
      return CHARACTER.matcher(value).matches() ? Inputs.characters(values) : Advice.join(values);
    }
  }

  /// `` `total >= 100` ``, `` `Files.isRegularFile(git)` `` for a test of a call, or "the `>=`
  /// check" / "the condition" when the operands aren't known. The condition as source, `total >=
  /// 100`, or `ready` for one operand: none when the run doesn't say what it compares.
  private static Optional<String> conditionSource(List<String> operands, Optional<String> token) {
    if (operands.size() == 1) {
      return Optional.of(operands.getFirst());
    }
    return operands.size() == 2
        ? token.map(each -> "%s %s %s".formatted(operands.get(0), each, operands.get(1)))
        : Optional.empty();
  }

  private static String condition(List<String> operands, Optional<String> token) {
    if (operands.size() == 1) {
      return "`%s`".formatted(operands.getFirst());
    }
    if (operands.size() == 2 && token.isPresent()) {
      return "`%s %s %s`".formatted(operands.get(0), token.get(), operands.get(1));
    }
    return token.map("the `%s` check"::formatted).orElse("the condition");
  }

  /// `` `discount()` ``, or "a lambda in `run()`" for javac's `lambda$run$0`.
  static String method(Mutant mutant) {
    var where = SourceNames.of(mutant);
    if (where.method().isEmpty()) {
      return where.kind().orElse("`%s`".formatted(where.className()));
    }
    return where
        .kind()
        .map(
            kind ->
                kind.equals("a lambda")
                    ? "a lambda in `%s()`".formatted(where.method())
                    : "`%s()` in %s".formatted(where.method(), kind))
        .orElse("`%s()`".formatted(where.method()));
  }

  private static List<String> inputsSeen(Inputs seen) {
    // A parameter the source doesn't have, as the Compose compiler's `$changed` mask, isn't an
    // input.
    var named =
        seen.values().entrySet().stream().filter(entry -> !entry.getKey().startsWith("$")).toList();
    return named.isEmpty()
        ? List.of()
        : List.of(
            "inputs seen: "
                + named.stream()
                    .map(entry -> seen.written(entry.getKey()))
                    .collect(Collectors.joining("; ")));
  }

  private static String join(List<Long> values) {
    return values.stream().map(String::valueOf).collect(Collectors.joining(", "));
  }

  /// `+` for `IADD` or `DADD`: the arithmetic operators only change these.
  static String arithmetic(String opcode) {
    return requireNonNull(ARITHMETIC.get(opcode.substring(1)), opcode);
  }

  private static String capitalised(String text) {
    return text.startsWith("a lambda") ? "A" + text.substring(1) : text;
  }

  /// `>=` for `IF_ICMPLT`, or `<` when the jump reads as written; empty for a test of a `boolean`
  /// (`IFEQ`, `IFNE`), whose source has no operator to show. Whether a jump on a boolean goes when
  /// it's true, as `IFNE` does: javac compiles the first test of an `||` that way, jumping into the
  /// body, and `if (!done)` past it. The opcode says, whatever the source looks like. Empty for a
  /// jump on a comparison, whose operands are two.
  static Optional<Boolean> jumpsWhenTrue(Mutant mutant) {
    var opcode = mutant.original();
    return mutant.operands().size() == 1 && (opcode.equals("IFEQ") || opcode.equals("IFNE"))
        ? Optional.of(opcode.equals("IFNE"))
        : Optional.empty();
  }

  /// The source operator a jump stands for. `IFEQ` and `IFNE` test a boolean, with nothing to show,
  /// or compare an int with zero, as in `count != 0`, when its operands are the int and `0`.
  static Optional<String> jump(String opcode, List<String> operands, boolean asWritten) {
    if ((opcode.equals("IFEQ") || opcode.equals("IFNE")) && operands.size() != 2) {
      return Optional.empty();
    }
    return token(opcode, asWritten);
  }

  /// The source operator a jump on a comparison stands for, `>=` for `IF_ICMPLT`.
  private static Optional<String> token(String opcode, boolean asWritten) {
    var condition = opcode.substring(opcode.startsWith("IF_") ? "IF_ICMP".length() : "IF".length());
    return Optional.ofNullable((asWritten ? JUMP_AS_WRITTEN : JUMP_TO_SOURCE).get(condition));
  }
}
