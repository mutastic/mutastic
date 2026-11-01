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

import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.util.Map;

/// The mutation operators, and what each instruction can become.
///
/// Operators work on two kinds of site, counted apart: instructions (arithmetic, branches, returns)
/// and calls. A new operator on calls never shifts the ids of instruction mutants.
public enum Operator {
  /// `<` becomes `<=` and the other way round: the off-by-one at the edge of a range, which tests
  /// that only check values far from the edge never notice.
  BOUNDARY(
      Map.of(
          IF_ICMPLT, IF_ICMPLE, IF_ICMPLE, IF_ICMPLT, IF_ICMPGT, IF_ICMPGE, IF_ICMPGE, IF_ICMPGT,
          IFLT, IFLE, IFLE, IFLT, IFGT, IFGE, IFGE, IFGT)),

  /// A condition goes the other way: `==` becomes `!=`, `<` becomes `>=`. Survives when no test
  /// checks what happens on one side of an `if`.
  NEGATE_CONDITIONAL(
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
          Map.entry(IF_ACMPNE, IF_ACMPEQ))),

  /// `+` becomes `-`, `*` becomes `/`, and back, on `int` and `long`.
  MATH(
      Map.of(
          IADD, ISUB, ISUB, IADD, IMUL, IDIV, IDIV, IMUL, LADD, LSUB, LSUB, LADD, LMUL, LDIV, LDIV,
          LMUL)),

  /// A method returns its type's default (`0`, `false`, `null`) instead of what it computed.
  /// Survives when tests call the method but never look at what it returns.
  RETURN_DEFAULT(Map.of()),

  /// A call that returns nothing is removed. Survives when no test checks the call's effect.
  REMOVE_VOID_CALL(Map.of());

  private final Map<Opcode, Opcode> replacements;

  Operator(Map<Opcode, Opcode> replacements) {
    this.replacements = replacements;
  }

  /// `mutastic/boundary`: how reports and rules name it.
  public String id() {
    return "mutastic/" + name().toLowerCase().replace('_', '-');
  }

  /// Whether it works on call sites rather than instruction sites.
  boolean onCalls() {
    return this == REMOVE_VOID_CALL;
  }

  boolean applies(Instruction instruction) {
    return switch (this) {
      case RETURN_DEFAULT ->
          instruction instanceof ReturnInstruction returning
              && returning.typeKind() != TypeKind.VOID;
      case REMOVE_VOID_CALL ->
          instruction instanceof InvokeInstruction call
              && call.typeSymbol().returnType().descriptorString().equals("V")
              && !call.name().equalsString("<init>");
      default ->
          (instruction instanceof BranchInstruction || instruction instanceof OperatorInstruction)
              && replacements.containsKey(instruction.opcode());
    };
  }

  /// What the instruction becomes, as it appears in ids: an opcode, or what changed. These labels
  /// are part of every mutant's fingerprint, so they never change.
  String replacement(Instruction instruction) {
    return switch (this) {
      case RETURN_DEFAULT -> "return default";
      case REMOVE_VOID_CALL -> "remove call";
      default -> replace(instruction.opcode()).name();
    };
  }

  Opcode replace(Opcode opcode) {
    return replacements.get(opcode);
  }
}
