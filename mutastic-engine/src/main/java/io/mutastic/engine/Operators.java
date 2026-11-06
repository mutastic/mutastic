package io.mutastic.engine;

import static java.lang.classfile.Opcode.GOTO;
import static java.lang.classfile.Opcode.GOTO_W;
import static java.lang.classfile.Opcode.IADD;
import static java.lang.classfile.Opcode.IDIV;
import static java.lang.classfile.Opcode.IMUL;
import static java.lang.classfile.Opcode.ISUB;
import static java.lang.classfile.Opcode.LADD;
import static java.lang.classfile.Opcode.LDIV;
import static java.lang.classfile.Opcode.LMUL;
import static java.lang.classfile.Opcode.LSUB;
import static java.util.Objects.requireNonNull;

import io.mutastic.api.BuiltIn;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/// How each [BuiltIn] operator finds its sites and what each instruction becomes.
final class Operators {
  /// `remove-conditional`'s two changes: the jump always taken, so the code it skips never runs, or
  /// never taken, so it always does.
  static final String ALWAYS_JUMP = "always jump";

  static final String NEVER_JUMP = "never jump";

  private static final Map<BuiltIn, Map<Opcode, Opcode>> REPLACEMENTS =
      new EnumMap<>(BuiltIn.class);

  static {
    REPLACEMENTS.put(
        BuiltIn.MATH,
        Map.of(
            IADD, ISUB, ISUB, IADD, IMUL, IDIV, IDIV, IMUL, LADD, LSUB, LSUB, LADD, LMUL, LDIV,
            LDIV, LMUL));
    REPLACEMENTS.put(BuiltIn.ARITHMETIC_REPLACEMENT, Map.of(IADD, IMUL, LADD, LMUL));
  }

  private Operators() {}

  /// What `operator` can make of `instruction`, as it appears in ids: an opcode, or what changed.
  /// These labels are part of every mutant's fingerprint, so they never change. None when the
  /// operator doesn't apply, or reads the code around the site, which `Mutations` asks it about.
  static List<String> replacements(BuiltIn operator, Instruction instruction) {
    return switch (operator) {
      case REMOVE_VOID_CALL ->
          instruction instanceof InvokeInstruction call
                  && call.typeSymbol().returnType().descriptorString().equals("V")
                  && !call.name().equalsString("<init>")
              ? List.of("remove call")
              : List.of();
      case REMOVE_CONDITIONAL ->
          isConditional(instruction) ? List.of(ALWAYS_JUMP, NEVER_JUMP) : List.of();
      case REMOVE_NEGATION -> Arithmetic.negations(instruction.opcode());
      default ->
          (instruction instanceof BranchInstruction || instruction instanceof OperatorInstruction)
                  && REPLACEMENTS.getOrDefault(operator, Map.of()).containsKey(instruction.opcode())
              ? List.of(replace(operator, instruction.opcode()).name())
              : List.of();
    };
  }

  /// What the opcode becomes, for an operator that swaps one opcode for another.
  static Opcode replace(BuiltIn operator, Opcode opcode) {
    return requireNonNull(
        REPLACEMENTS.getOrDefault(operator, Map.of()).get(opcode),
        () -> operator.name() + " doesn't apply to " + opcode);
  }

  /// A jump that tests something: every branch but `goto`.
  static boolean isConditional(Instruction instruction) {
    return instruction instanceof BranchInstruction
        && instruction.opcode() != GOTO
        && instruction.opcode() != GOTO_W;
  }

  /// How many stack slots a conditional jump tests: two for a comparison, one against zero or null.
  static int tested(Opcode opcode) {
    return switch (opcode) {
      case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE ->
          2;
      default -> 1;
    };
  }
}
