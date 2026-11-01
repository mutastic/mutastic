package io.mutastic.report;

import java.util.Map;
import java.util.Optional;

/// What the instructions mutastic changes do, in words: for `explain`, which shows a mutant in the
/// bytecode as well as the source.
final class Opcodes {
  private Opcodes() {}

  private static final Map<String, String> CONDITIONS =
      Map.of(
          "EQ",
          "equal to",
          "NE",
          "not equal to",
          "LT",
          "less than",
          "GE",
          "greater than or equal to",
          "GT",
          "greater than",
          "LE",
          "less than or equal to");

  private static final Map<String, String> ARITHMETIC =
      Map.of("ADD", "add", "SUB", "subtract", "MUL", "multiply", "DIV", "divide");

  private static final Map<String, String> TYPES =
      Map.of("I", "ints", "L", "longs", "F", "floats", "D", "doubles", "A", "references");

  /// `IF_ICMPLT`: `jump if the first int is less than the second`; empty for one it can't say.
  static Optional<String> describe(String opcode) {
    if (opcode.startsWith("IF_ICMP")) {
      return condition(opcode.substring(7))
          .map(words -> "jump if the first int is " + words + " the second");
    }
    if (opcode.startsWith("IF_ACMP")) {
      return Optional.of(
          opcode.endsWith("EQ")
              ? "jump if both are the same object"
              : "jump if they're different objects");
    }
    if (opcode.equals("IFNULL") || opcode.equals("IFNONNULL")) {
      return Optional.of(opcode.equals("IFNULL") ? "jump if it's null" : "jump if it isn't null");
    }
    if (opcode.startsWith("IF")) {
      return condition(opcode.substring(2))
          .map(
              words ->
                  "jump if the int (or the comparison's result, or a boolean) is "
                      + words
                      + " zero");
    }
    return arithmetic(opcode).or(() -> returns(opcode)).or(() -> call(opcode));
  }

  private static final Map<String, String> SYMBOLS =
      Map.of("EQ", "==", "NE", "!=", "LT", "<", "GE", ">=", "GT", ">", "LE", "<=");

  /// `<` for `IF_ICMPLT`: the comparison an int-comparing jump makes; empty for any other.
  static Optional<String> symbol(String opcode) {
    return opcode.startsWith("IF_ICMP")
        ? Optional.ofNullable(SYMBOLS.get(opcode.substring(7)))
        : Optional.empty();
  }

  private static Optional<String> condition(String suffix) {
    return Optional.ofNullable(CONDITIONS.get(suffix));
  }

  /// `IADD`: `add two ints`.
  private static Optional<String> arithmetic(String opcode) {
    if (opcode.length() < 4) {
      return Optional.empty();
    }
    var operation = ARITHMETIC.get(opcode.substring(1));
    var type = TYPES.get(opcode.substring(0, 1));
    return operation == null || type == null
        ? Optional.empty()
        : Optional.of(operation + " two " + type);
  }

  /// `IRETURN`: `return an int`; `return default`: `return 0, false or null instead`.
  private static Optional<String> returns(String opcode) {
    if (opcode.equals("return default")) {
      return Optional.of("return the type's default (0, false or null) instead");
    }
    if (opcode.endsWith("RETURN") && opcode.length() == 7) {
      var type = TYPES.get(opcode.substring(0, 1));
      return type == null
          ? Optional.empty()
          : Optional.of("return the value on the stack (one of the " + type + ")");
    }
    return Optional.empty();
  }

  /// `INVOKEVIRTUAL com/acme/Audit.record(…)V`: `call Audit.record()`; `remove call`.
  private static Optional<String> call(String opcode) {
    if (opcode.equals("remove call")) {
      return Optional.of("nothing: the call is gone, and so is whatever it would have done");
    }
    if (!opcode.startsWith("INVOKE")) {
      return Optional.empty();
    }
    int space = opcode.indexOf(' ');
    int paren = opcode.indexOf('(');
    if (space < 0 || paren < 0) {
      return Optional.empty();
    }
    var target = opcode.substring(space + 1, paren);
    return Optional.of("call " + target.substring(target.lastIndexOf('/') + 1) + "()");
  }
}
