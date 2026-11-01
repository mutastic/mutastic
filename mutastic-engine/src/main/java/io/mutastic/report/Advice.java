package io.mutastic.report;

import io.mutastic.engine.Mutant;
import java.lang.constant.MethodTypeDesc;
import java.util.Map;
import java.util.Optional;

/// What a mutant changed and what test would catch it, in source terms, written per operator.
///
/// @param before the source token the mutant changes, `>=`, when the bytecode says which
/// @param after what it becomes, `>`
/// @param change the change in words, for when the line can't be rewritten
/// @param headline the gap in the tests, for a missed mutant
/// @param fix the test to write
public record Advice(Optional<String> before, Optional<String> after, String change, String headline, String fix) {

  /// javac compiles an `if` to a jump past its body, so the jump tests the opposite of the
  /// source: `if (total >= 100)` becomes `IF_ICMPLT`, "jump if less". The source operator is
  /// the negation of the jump's. Loops and `?:` compile the same way; a `do … while` doesn't,
  /// and its line is then not rewritten, because the operator isn't found on it.
  private static final Map<String, String> JUMP_TO_SOURCE = Map.of(
      "EQ", "!=", "NE", "==", "LT", ">=", "GE", "<", "GT", "<=", "LE", ">", "NULL", "!=", "NONNULL", "==");

  private static final Map<String, String> ARITHMETIC = Map.of(
      "ADD", "+", "SUB", "-", "MUL", "*", "DIV", "/");

  public static Advice of(Mutant mutant) {
    var method = "`%s()`".formatted(mutant.methodName());
    return switch (mutant.operator()) {
      case BOUNDARY -> {
        var before = jump(mutant.original()).orElseThrow();
        var after = jump(mutant.replacement()).orElseThrow();
        yield new Advice(Optional.of(before), Optional.of(after), "`%s` becomes `%s`".formatted(before, after),
            "no test checks the edge of `%s` in %s".formatted(before, method),
            "add a test with the values where `%s` and `%s` disagree (both sides equal), and assert the result"
                .formatted(before, after));
      }
      case NEGATE_CONDITIONAL -> {
        var before = jump(mutant.original());
        var after = jump(mutant.replacement());
        var condition = before.map("`%s`"::formatted).orElse("the condition");
        yield new Advice(before, after,
            before.isPresent() ? "`%s` becomes `%s`".formatted(before.get(), after.get()) : "the condition is inverted",
            "no test checks both outcomes of %s in %s".formatted(condition, method),
            "add a test where %s goes the other way, and assert the result".formatted(condition));
      }
      case MATH -> {
        var before = ARITHMETIC.get(mutant.original().substring(1));
        var after = ARITHMETIC.get(mutant.replacement().substring(1));
        yield new Advice(Optional.of(before), Optional.of(after), "`%s` becomes `%s`".formatted(before, after),
            "no test checks the result of `%s` in %s".formatted(before, method),
            "assert a result that depends on `%s`, with values where `%s` and `%s` give different answers"
                .formatted(before, before, after));
      }
      case RETURN_DEFAULT -> {
        var value = defaultValue(mutant.descriptor());
        yield new Advice(Optional.empty(), Optional.of("return " + value + ";"), "returns `%s` instead".formatted(value),
            "no test checks what %s returns here".formatted(method),
            "assert the value %s returns on this path".formatted(method));
      }
      case REMOVE_VOID_CALL -> {
        var called = "`%s()`".formatted(calledName(mutant.original()));
        yield new Advice(Optional.empty(), Optional.empty(), "the call to %s is removed".formatted(called),
            "no test checks the effect of calling %s in %s".formatted(called, method),
            "assert what %s does: the state it changes or the call it makes".formatted(called));
      }
    };
  }

  /// `>=` for `IF_ICMPLT`; empty for a test of a `boolean` (`IFEQ`, `IFNE`), whose source has
  /// no operator to show.
  private static Optional<String> jump(String opcode) {
    if (opcode.equals("IFEQ") || opcode.equals("IFNE")) {
      return Optional.empty();
    }
    var condition = opcode.substring(opcode.startsWith("IF_") ? "IF_ICMP".length() : "IF".length());
    return Optional.ofNullable(JUMP_TO_SOURCE.get(condition));
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

  private static String calledName(String call) {
    return SourceView.calledName(call);
  }
}
