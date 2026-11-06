package io.mutastic.report;

import static java.util.Objects.requireNonNull;

import io.mutastic.api.BuiltIn;
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
/// @param changedTo the change as the label under it in a code frame: `changed to >`
/// @param headline the gap in the tests, for a missed mutant
/// @param notes the evidence: what the tests did
/// @param fix the test to write
public record Advice(
    Optional<String> before,
    Optional<String> after,
    String change,
    String changedTo,
    String headline,
    List<String> notes,
    String fix) {

  public Advice {
    notes = List.copyOf(notes);
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
      Map.of("ADD", "+", "SUB", "-", "MUL", "*", "DIV", "/", "REM", "%");

  private static final Pattern NUMBER = Pattern.compile("-?\\d+");

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
  static Advice of(Mutant mutant, Map<String, List<Long>> seen, boolean asWritten) {
    boolean written = jumpsWhenTrue(mutant).orElse(asWritten);
    return switch (mutant.operator()) {
      case Mutator mutator -> added(mutator, mutant, seen);
      case BuiltIn builtIn -> ofBuiltIn(builtIn, mutant, seen, written);
    };
  }

  private static Advice ofBuiltIn(
      BuiltIn builtIn, Mutant mutant, Map<String, List<Long>> seen, boolean asWritten) {
    return switch (builtIn) {
      case BOUNDARY, COMPARISON, OTHER_COMPARISON -> comparison(mutant, seen, asWritten);
      case MATH, ARITHMETIC_REPLACEMENT, OTHER_ARITHMETIC -> math(mutant, seen);
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
      case REMOVE_SWITCH_ARM -> switchArm(mutant, seen, UnaryOperator.identity());
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
  static Advice connector(
      Mutant mutant, Map<String, List<Long>> seen, Optional<Connective> connective) {
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
        "changed to " + known.other(),
        "no test checks %s with %s true and the other false".formatted(method, sides),
        inputsSeen(seen),
        "add a test where %s is true and the other false, and assert what %s does then"
            .formatted(sides, method));
  }

  /// A connector the source doesn't say: the statement has more than one, or isn't there.
  private static Advice unnamedConnector(String method, Map<String, List<Long>> seen) {
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
  private static Advice added(Mutator mutator, Mutant mutant, Map<String, List<Long>> seen) {
    var words = mutator.words(mutant);
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
  private static Advice removeConditional(
      Mutant mutant, Map<String, List<Long>> seen, boolean asWritten) {
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
            .formatted(condition, other, method));
  }

  /// A comparison changed, by where the two comparisons give different answers: only where its two
  /// sides are equal, its edge moved (`<` to `<=`), and the advice is the boundary's; everywhere,
  /// the condition reversed; otherwise on one side of the edge (`<` to `!=` disagree where `a >
  /// b`), and the advice names that side.
  private static Advice comparison(Mutant mutant, Map<String, List<Long>> seen, boolean asWritten) {
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
  private static Advice sided(Mutant mutant, Map<String, List<Long>> seen, Change change) {
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
        "changed to " + change.after(),
        headline,
        sidedNotes(method, operands, change.differ(), seen),
        fix);
  }

  /// That no test's input is on the side where the two disagree, when the inputs show it: the name
  /// compared with a number, and every value it was seen with on the other side.
  private static List<String> sidedNotes(
      String method, List<String> operands, Sides differ, Map<String, List<Long>> seen) {
    var comparison = Comparison.of(operands);
    if (comparison.isEmpty()
        || !NUMBER.matcher(comparison.get().value()).matches()
        || !seen.containsKey(comparison.get().name())) {
      return inputsSeen(seen);
    }
    long value = Long.parseLong(comparison.get().value());
    // The name's side of the comparison, as the source has it: `100 < total` has `total` second.
    boolean nameFirst = operands.get(0).equals(comparison.get().name());
    var values = seen.get(comparison.get().name());
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
        "inputs seen: %s".formatted(join(values)));
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
  private static Advice boundary(
      Mutant mutant, Map<String, List<Long>> seen, String before, String after) {
    var method = method(mutant);
    var comparison = Comparison.of(mutant.operands());
    return new Advice(
        Optional.of(before),
        Optional.of(after),
        "`%s` becomes `%s`".formatted(before, after),
        "changed to " + after,
        boundaryHeadline(method, comparison, before),
        boundaryNotes(method, comparison, seen),
        boundaryFix(method, comparison, before, after));
  }

  /// That every test stays away from the boundary, when the inputs show it; else the inputs seen.
  private static List<String> boundaryNotes(
      String method, Optional<Comparison> comparison, Map<String, List<Long>> seen) {
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
                                    .formatted(join(values), found.value()))))
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

  private static Advice negateConditional(
      Mutant mutant, Map<String, List<Long>> seen, boolean asWritten) {
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
        before.map(token -> "changed to " + after.get()).orElse("reversed"),
        "%s behaves the same with %s reversed".formatted(capitalised(method), condition),
        inputsSeen(seen),
        "assert the result on both sides of " + condition);
  }

  private static Advice math(Mutant mutant, Map<String, List<Long>> seen) {
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
        "changed to " + after,
        "tests pass even when `%s` becomes `%s` in %s".formatted(before, after, method),
        inputsSeen(seen),
        "use inputs where %s and %s differ, and assert the result".formatted(original, swapped));
  }

  /// A calculation down to one of its values: the test to add is one the other changes the result
  /// of. The label says which is kept, `keep first`.
  private static Advice keptOperand(Mutant mutant, Map<String, List<Long>> seen) {
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

  private static Advice kept(
      String change, String headline, String fix, Map<String, List<Long>> seen) {
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
  private static Advice returnValue(Mutant mutant, Map<String, List<Long>> seen) {
    var method = method(mutant);
    var value = mutant.replacement().substring("return ".length());
    var notes = new ArrayList<String>();
    notes.add("tests reach this return, but none fails when it returns `%s`".formatted(value));
    notes.addAll(inputsSeen(seen));
    var today = returnedToday(value);
    return new Advice(
        Optional.empty(),
        Optional.of(mutant.replacement() + ";"),
        "returns `%s` instead".formatted(value),
        "now returns " + value,
        today
            .map(was -> "no test checks %s returning `%s` here".formatted(method, was))
            .orElse("no test checks what %s returns here".formatted(method)),
        notes,
        today
            .map(
                was ->
                    "add a test where %s returns `%s` here, and assert it".formatted(method, was))
            .orElseGet(() -> returnHelp(method, value)));
  }

  /// What a return gives today, when the mutant's value says: the other `boolean`, or the `0` a
  /// mutant that returns `1` took away.
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
    var other = value.startsWith("0") ? "`" + value + "`" : "empty";
    return "assert the value %s returns on this path, with inputs where it isn't %s"
        .formatted(method, other);
  }

  /// An assignment or a field write taken away: what's stored keeps the value it had.
  private static Advice removedStore(Mutant mutant, Map<String, List<Long>> seen, String kind) {
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
  private static Advice omittedCall(Mutant mutant, Map<String, List<Long>> seen) {
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
  private static Advice swappedArguments(Mutant mutant, Map<String, List<Long>> seen) {
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
  private static Advice nullParameter(Mutant mutant, Map<String, List<Long>> seen) {
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
  private static Advice droppedElement(Mutant mutant, Map<String, List<Long>> seen) {
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
  static Advice constant(
      Mutant mutant, Map<String, List<Long>> seen, UnaryOperator<String> written) {
    var label = mutant.replacement().split(" ", 4);
    var today = written.apply(label[1]);
    var mutated = written.apply(label[3]);
    return new Advice(
        Optional.of(today),
        Optional.of(mutated),
        "the constant `%s` becomes `%s`".formatted(today, mutated),
        "changed to " + mutated,
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
  static Advice switchArm(
      Mutant mutant, Map<String, List<Long>> seen, UnaryOperator<String> written) {
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

  /// A variable's step the other way. The label is the step the mutant takes, `i by -1`.
  private static Advice flippedIncrement(Mutant mutant, Map<String, List<Long>> seen) {
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
  private static Advice removedNegation(Mutant mutant, Map<String, List<Long>> seen) {
    return new Advice(
        Optional.empty(),
        Optional.empty(),
        "a minus sign is removed, so the value keeps its sign",
        "minus sign removed",
        "no test checks the sign of the negated value in %s".formatted(method(mutant)),
        inputsSeen(seen),
        "add a test with a value that isn't zero, and assert the sign of the result");
  }

  private static Advice removeVoidCall(Mutant mutant, Map<String, List<Long>> seen) {
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

  /// A comparison of a name with a value, the name first however the source has it: `total >= 100`
  /// and `100 <= total` both give `total` and `100`.
  private record Comparison(String name, String value) {
    static Optional<Comparison> of(List<String> operands) {
      if (operands.size() != 2) {
        return Optional.empty();
      }
      boolean leftNumber = NUMBER.matcher(operands.get(0)).matches();
      boolean rightNumber = NUMBER.matcher(operands.get(1)).matches();
      if (leftNumber && !rightNumber) {
        return Optional.of(new Comparison(operands.get(1), operands.get(0)));
      }
      return Optional.of(new Comparison(operands.get(0), operands.get(1)));
    }

    /// The inputs the tests passed for the name, when it's a parameter compared with a number and
    /// none of them is that number.
    Optional<List<Long>> boundary(Map<String, List<Long>> seen) {
      var values = seen.get(name);
      if (values == null || values.isEmpty() || !NUMBER.matcher(value).matches()) {
        return Optional.empty();
      }
      return values.contains(Long.parseLong(value)) ? Optional.empty() : Optional.of(values);
    }
  }

  /// `` `total >= 100` ``, `` `Files.isRegularFile(git)` `` for a test of a call, or "the `>=`
  /// check" / "the condition" when the operands aren't known.
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
    return where
        .kind()
        .map(
            kind ->
                kind.equals("a lambda")
                    ? "a lambda in `%s()`".formatted(where.method())
                    : "`%s()` in %s".formatted(where.method(), kind))
        .orElse("`%s()`".formatted(where.method()));
  }

  private static List<String> inputsSeen(Map<String, List<Long>> seen) {
    return seen.isEmpty()
        ? List.of()
        : List.of(
            "inputs seen: "
                + seen.entrySet().stream()
                    .map(entry -> entry.getKey() + " = " + join(entry.getValue()))
                    .collect(Collectors.joining("; ")));
  }

  private static String join(List<Long> values) {
    return values.stream().map(String::valueOf).collect(Collectors.joining(", "));
  }

  /// `+` for `IADD` or `DADD`: the arithmetic operators only change these.
  private static String arithmetic(String opcode) {
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
