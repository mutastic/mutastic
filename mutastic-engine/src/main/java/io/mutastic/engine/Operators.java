package io.mutastic.engine;

import static java.lang.classfile.Opcode.IADD;
import static java.lang.classfile.Opcode.IDIV;
import static java.lang.classfile.Opcode.IFEQ;
import static java.lang.classfile.Opcode.IFGE;
import static java.lang.classfile.Opcode.IFGT;
import static java.lang.classfile.Opcode.IFLE;
import static java.lang.classfile.Opcode.IFLT;
import static java.lang.classfile.Opcode.IFNE;
import static java.lang.classfile.Opcode.IFNONNULL;
import static java.lang.classfile.Opcode.IFNULL;
import static java.lang.classfile.Opcode.IF_ACMPEQ;
import static java.lang.classfile.Opcode.IF_ACMPNE;
import static java.lang.classfile.Opcode.IF_ICMPEQ;
import static java.lang.classfile.Opcode.IF_ICMPGE;
import static java.lang.classfile.Opcode.IF_ICMPGT;
import static java.lang.classfile.Opcode.IF_ICMPLE;
import static java.lang.classfile.Opcode.IF_ICMPLT;
import static java.lang.classfile.Opcode.IF_ICMPNE;
import static java.lang.classfile.Opcode.IMUL;
import static java.lang.classfile.Opcode.ISUB;
import static java.lang.classfile.Opcode.LADD;
import static java.lang.classfile.Opcode.LDIV;
import static java.lang.classfile.Opcode.LMUL;
import static java.lang.classfile.Opcode.LSUB;
import static java.util.Objects.requireNonNull;

import io.mutastic.api.Operator;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.util.EnumMap;
import java.util.Map;

/// How each [Operator] finds its sites and what each instruction becomes.
final class Operators {
  private static final Map<Operator, Map<Opcode, Opcode>> REPLACEMENTS =
      new EnumMap<>(Operator.class);

  static {
    REPLACEMENTS.put(
        Operator.BOUNDARY,
        Map.of(
            IF_ICMPLT, IF_ICMPLE, IF_ICMPLE, IF_ICMPLT, IF_ICMPGT, IF_ICMPGE, IF_ICMPGE, IF_ICMPGT,
            IFLT, IFLE, IFLE, IFLT, IFGT, IFGE, IFGE, IFGT));
    REPLACEMENTS.put(
        Operator.NEGATE_CONDITIONAL,
        Map.ofEntries(
            Map.entry(IF_ICMPLT, IF_ICMPGE),
            Map.entry(IF_ICMPGE, IF_ICMPLT),
            Map.entry(IF_ICMPGT, IF_ICMPLE),
            Map.entry(IF_ICMPLE, IF_ICMPGT),
            Map.entry(IF_ICMPEQ, IF_ICMPNE),
            Map.entry(IF_ICMPNE, IF_ICMPEQ),
            Map.entry(IFEQ, IFNE),
            Map.entry(IFNE, IFEQ),
            Map.entry(IFLT, IFGE),
            Map.entry(IFGE, IFLT),
            Map.entry(IFGT, IFLE),
            Map.entry(IFLE, IFGT),
            Map.entry(IFNULL, IFNONNULL),
            Map.entry(IFNONNULL, IFNULL),
            Map.entry(IF_ACMPEQ, IF_ACMPNE),
            Map.entry(IF_ACMPNE, IF_ACMPEQ)));
    REPLACEMENTS.put(
        Operator.MATH,
        Map.of(
            IADD, ISUB, ISUB, IADD, IMUL, IDIV, IDIV, IMUL, LADD, LSUB, LSUB, LADD, LMUL, LDIV,
            LDIV, LMUL));
    REPLACEMENTS.put(Operator.RETURN_DEFAULT, Map.of());
    REPLACEMENTS.put(Operator.REMOVE_VOID_CALL, Map.of());
  }

  private Operators() {}

  static boolean applies(Operator operator, Instruction instruction) {
    return switch (operator) {
      case RETURN_DEFAULT ->
          instruction instanceof ReturnInstruction returning
              && returning.typeKind() != TypeKind.VOID;
      case REMOVE_VOID_CALL ->
          instruction instanceof InvokeInstruction call
              && call.typeSymbol().returnType().descriptorString().equals("V")
              && !call.name().equalsString("<init>");
      default ->
          (instruction instanceof BranchInstruction || instruction instanceof OperatorInstruction)
              && replacements(operator).containsKey(instruction.opcode());
    };
  }

  /// What the instruction becomes, as it appears in ids: an opcode, or what changed. These labels
  /// are part of every mutant's fingerprint, so they never change.
  static String replacement(Operator operator, Instruction instruction) {
    return switch (operator) {
      case RETURN_DEFAULT -> "return default";
      case REMOVE_VOID_CALL -> "remove call";
      default -> replace(operator, instruction.opcode()).name();
    };
  }

  /// What the opcode becomes; only for an instruction [#applies(Operator, Instruction)] accepts.
  static Opcode replace(Operator operator, Opcode opcode) {
    return requireNonNull(
        replacements(operator).get(opcode), () -> operator.name() + " doesn't apply to " + opcode);
  }

  private static Map<Opcode, Opcode> replacements(Operator operator) {
    return REPLACEMENTS.getOrDefault(operator, Map.of());
  }
}
