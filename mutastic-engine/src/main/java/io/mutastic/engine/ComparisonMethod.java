package io.mutastic.engine;

import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.util.List;
import java.util.Optional;

/// A static method of the class whose whole body compares its `int` parameter with a constant,
/// `static boolean inCredit(int balance) { return balance >= 1; }`: the values it's true for.
///
/// javac compiles it to `iload_0; push c; if not ≥ goto L; iconst_1; goto E; L: iconst_0; E:
/// ireturn`. A static method of the same class is the one that runs, so a call to it can be read as
/// its comparison.
final class ComparisonMethod {
  private ComparisonMethod() {}

  static Optional<ImpliedOr.Range> of(ClassModel owner, InvokeInstruction call) {
    if (call.opcode() != Opcode.INVOKESTATIC
        || !call.owner().asInternalName().equals(owner.thisClass().asInternalName())
        || !call.typeSymbol().descriptorString().equals("(I)Z")) {
      return Optional.empty();
    }
    return owner.methods().stream()
        .filter(method -> method.methodName().equalsString(call.name().stringValue()))
        .filter(method -> method.methodType().equalsString(call.type().stringValue()))
        .flatMap(method -> method.code().stream())
        .findFirst()
        .flatMap(code -> trueFor(code.elementList()));
  }

  /// `iload_0; push c; IF_ICMPxx L; iconst_1; goto E | ireturn; L: iconst_0; [E:] ireturn`: true
  /// when the jump isn't taken. The instructions are matched, and where the jump goes; the labels a
  /// local variable table adds at the start and end are none of its business.
  private static Optional<ImpliedOr.Range> trueFor(List<CodeElement> code) {
    var body =
        code.stream().filter(Instruction.class::isInstance).map(Instruction.class::cast).toList();
    if (body.size() != 7
        || !(body.get(0) instanceof LoadInstruction load && load.slot() == 0)
        || !(body.get(1) instanceof ConstantInstruction constant
            && constant.constantValue() instanceof Integer value)
        || !(body.get(2) instanceof BranchInstruction jump
            && jump.opcode().name().startsWith("IF_ICMP"))
        || !returns(body, code, jump)) {
      return Optional.empty();
    }
    return ImpliedOr.range(
        ImpliedOr.negated(ImpliedOr.condition(jump.opcode().name())), Optional.of(value));
  }

  /// `iconst_1; goto | ireturn; iconst_0; ireturn`, the jump going to the `iconst_0`.
  private static boolean returns(
      List<Instruction> body, List<CodeElement> code, BranchInstruction jump) {
    return body.get(3).opcode() == Opcode.ICONST_1
        && (body.get(4).opcode() == Opcode.GOTO || body.get(4).opcode() == Opcode.IRETURN)
        && body.get(5).opcode() == Opcode.ICONST_0
        && body.get(6).opcode() == Opcode.IRETURN
        && landsOn(code, jump, body.get(5));
  }

  /// Whether the first instruction after the jump's label is `instruction`.
  private static boolean landsOn(
      List<CodeElement> code, BranchInstruction jump, Instruction instruction) {
    boolean past = false;
    for (var element : code) {
      if (element instanceof LabelTarget target && target.label().equals(jump.target())) {
        past = true;
      } else if (past && element instanceof Instruction found) {
        return found == instruction;
      }
    }
    return false;
  }
}
