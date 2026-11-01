package io.mutastic.report;

import static java.util.Objects.requireNonNull;

import io.mutastic.engine.Mutant;
import io.mutastic.engine.MutantResult;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
      Map.of("ADD", "+", "SUB", "-", "MUL", "*", "DIV", "/");

  private static final Pattern LAMBDA = Pattern.compile("lambda\\$(?<enclosing>.+)\\$\\d+");
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
    var method = method(mutant);
    var operands = mutant.operands();
    var notes = new ArrayList<String>();
    return switch (mutant.operator()) {
      case BOUNDARY -> {
        var before = jump(mutant.original(), asWritten).orElseThrow();
        var after = jump(mutant.replacement(), asWritten).orElseThrow();
        var comparison = Comparison.of(operands);
        comparison
            .flatMap(found -> found.boundary(seen))
            .ifPresent(
                values -> {
                  notes.add("every test calls %s away from the boundary".formatted(method));
                  notes.add(
                      "inputs seen: %s (none is %s)"
                          .formatted(join(values), comparison.get().value()));
                });
        if (notes.isEmpty()) {
          notes.addAll(inputsSeen(seen));
        }
        yield new Advice(
            Optional.of(before),
            Optional.of(after),
            "`%s` becomes `%s`".formatted(before, after),
            "changed to " + after,
            comparison
                .map(
                    found ->
                        "no test checks %s at `%s == %s`"
                            .formatted(method, found.name(), found.value()))
                .orElse("no test checks the boundary of `%s` in %s".formatted(before, method)),
            notes,
            comparison
                .map(
                    found ->
                        "test %s with `%s = %s` and assert the result"
                            .formatted(method, found.name(), found.value()))
                .orElse(
                    "add a test at the boundary value, where `%s` and `%s` disagree, and assert the result"
                        .formatted(before, after)));
      }
      case NEGATE_CONDITIONAL -> {
        var before = jump(mutant.original(), asWritten);
        var after = jump(mutant.replacement(), asWritten);
        var condition = condition(operands, before);
        notes.addAll(inputsSeen(seen));
        yield new Advice(
            before,
            after,
            before.isPresent()
                ? "`%s` becomes `%s`".formatted(before.get(), after.get())
                : "the condition is reversed",
            before.map(token -> "changed to " + after.get()).orElse("reversed"),
            "%s behaves the same with %s reversed".formatted(capitalised(method), condition),
            notes,
            "assert the result on both sides of " + condition);
      }
      case MATH -> {
        var before = arithmetic(mutant.original());
        var after = arithmetic(mutant.replacement());
        boolean known = operands.size() == 2;
        var original =
            known
                ? "`%s %s %s`".formatted(operands.get(0), before, operands.get(1))
                : "`" + before + "`";
        var swapped =
            known
                ? "`%s %s %s`".formatted(operands.get(0), after, operands.get(1))
                : "`" + after + "`";
        notes.addAll(inputsSeen(seen));
        yield new Advice(
            Optional.of(before),
            Optional.of(after),
            "`%s` becomes `%s`".formatted(before, after),
            "changed to " + after,
            "tests pass even when `%s` becomes `%s` in %s".formatted(before, after, method),
            notes,
            "use inputs where %s and %s differ, and assert the result"
                .formatted(original, swapped));
      }
      case RETURN_DEFAULT -> {
        var value = defaultValue(mutant.descriptor());
        notes.add("tests reach this return but never assert its value");
        notes.addAll(inputsSeen(seen));
        yield new Advice(
            Optional.empty(),
            Optional.of("return " + value + ";"),
            "returns `%s` instead".formatted(value),
            "now returns " + value,
            "no test checks what %s returns here".formatted(method),
            notes,
            "assert the value %s returns on this path".formatted(method));
      }
      case REMOVE_VOID_CALL -> {
        var called = "`%s()`".formatted(SourceView.calledName(mutant.original()));
        notes.addAll(inputsSeen(seen));
        yield new Advice(
            Optional.empty(),
            Optional.empty(),
            "the call to %s is removed".formatted(called),
            "call removed",
            "no test checks the effect of calling %s in %s".formatted(called, method),
            notes,
            "assert what %s does: the state it changes or the call it makes".formatted(called));
      }
    };
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
    var lambda = LAMBDA.matcher(mutant.methodName());
    return lambda.matches()
        ? "a lambda in `%s()`".formatted(lambda.group("enclosing"))
        : "`%s()`".formatted(mutant.methodName());
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

  /// `+` for `IADD` or `LADD`: the math operator only changes these.
  private static String arithmetic(String opcode) {
    return requireNonNull(ARITHMETIC.get(opcode.substring(1)), opcode);
  }

  private static String capitalised(String text) {
    return text.startsWith("a lambda") ? "A" + text.substring(1) : text;
  }

  /// `>=` for `IF_ICMPLT`, or `<` when the jump reads as written; empty for a test of a `boolean`
  /// (`IFEQ`, `IFNE`), whose source has no operator to show.
  static Optional<String> jump(String opcode, boolean asWritten) {
    if (opcode.equals("IFEQ") || opcode.equals("IFNE")) {
      return Optional.empty();
    }
    var condition = opcode.substring(opcode.startsWith("IF_") ? "IF_ICMP".length() : "IF".length());
    return Optional.ofNullable((asWritten ? JUMP_AS_WRITTEN : JUMP_TO_SOURCE).get(condition));
  }

  /// `0`, `false`, `null`: what the method returns instead, from its descriptor.
  private static String defaultValue(String descriptor) {
    return switch (MethodTypeDesc.ofDescriptor(descriptor).returnType().descriptorString()) {
      case "Z" -> "false";
      case "J" -> "0L";
      case "F" -> "0.0f";
      case "D" -> "0.0";
      case "I", "S", "B", "C" -> "0";
      default -> "null";
    };
  }
}
