package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.util.List;

/// The operators that change a calculation beyond [BuiltIn#MATH]'s swaps: what each makes of an
/// instruction, and how it's written.
final class Arithmetic {
  static final String REMOVE_NEGATION = "remove negation";

  private Arithmetic() {}

  /// Whether the step has another way to go: not `0`, and not `-32768`, as `iinc` has no `32768`.
  static boolean flips(IncrementInstruction step) {
    return step.constant() != 0 && step.constant() != Short.MIN_VALUE;
  }

  /// The change as it appears in ids: the variable, and the step it takes now, `i by -1`.
  static String flipped(String name, IncrementInstruction step) {
    return "%s by %d".formatted(name, -step.constant());
  }

  /// A negation's change, for each of its four types.
  static List<String> negations(Opcode opcode) {
    return switch (opcode) {
      case INEG, LNEG, FNEG, DNEG -> List.of(REMOVE_NEGATION);
      default -> List.of();
    };
  }

  /// An arithmetic instruction as the mutant has it.
  static void write(CodeBuilder builder, OperatorInstruction arithmetic, BuiltIn operator) {
    if (operator != BuiltIn.REMOVE_NEGATION) { // otherwise, the value just stays as it is
      builder.with(OperatorInstruction.of(Operators.replace(operator, arithmetic.opcode())));
    }
  }
}
