package io.mutastic.engine;

import java.lang.classfile.Instruction;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.util.List;
import java.util.Optional;

/// Which instruction put a value a call takes on the stack, read back from the instructions before
/// it in a straight line. Values, not slots: a `long` is one. Empty when the code before it does
/// something this doesn't follow, a `dup` or a jump, so no guess is made.
final class StackProducers {
  private StackProducers() {}

  /// What one instruction takes off the stack and puts on, in values.
  record Effect(int pops, int pushes) {}

  /// The instruction that pushed the value `below` places under the top before `instructions[at]`
  /// runs: 1 is its last argument, and for a call with `n` arguments, `n` is its first and `n + 1`
  /// its receiver. `jumpTargets` are the places a jump can land, where nothing before is the only
  /// way in.
  static Optional<Integer> producer(
      List<Instruction> instructions, int at, int below, java.util.Set<Integer> jumpTargets) {
    int depth = below;
    for (int before = at - 1; before >= 0; before--) {
      if (jumpTargets.contains(before + 1)) {
        return Optional.empty();
      }
      var effect = effect(instructions.get(before));
      if (effect.isEmpty()) {
        return Optional.empty();
      }
      int pushes = effect.get().pushes();
      if (depth <= pushes) {
        return pushes == 1 ? Optional.of(before) : Optional.empty();
      }
      depth = depth - pushes + effect.get().pops();
    }
    return Optional.empty();
  }

  static Optional<Effect> effect(Instruction instruction) {
    return switch (instruction) {
      case LoadInstruction _, ConstantInstruction _, NewObjectInstruction _ ->
          Optional.of(new Effect(0, 1));
      case FieldInstruction field ->
          Optional.of(
              switch (field.opcode()) {
                case GETFIELD -> new Effect(1, 1);
                case GETSTATIC -> new Effect(0, 1);
                case PUTFIELD -> new Effect(2, 0);
                default -> new Effect(1, 0);
              });
      case InvokeInstruction call -> Optional.of(callEffect(call));
      case InvokeDynamicInstruction dynamic ->
          Optional.of(new Effect(dynamic.typeSymbol().parameterCount(), 1)); // a lambda made
      case ConvertInstruction _, TypeCheckInstruction _ ->
          Optional.of(new Effect(1, 1)); // instanceof is one in, one out too
      case ArrayLoadInstruction _ -> Optional.of(new Effect(2, 1));
      case OperatorInstruction operator -> Optional.of(operatorEffect(operator));
      default -> Optional.empty();
    };
  }

  private static Effect callEffect(InvokeInstruction call) {
    var type = call.typeSymbol();
    int receiver = call.opcode() == java.lang.classfile.Opcode.INVOKESTATIC ? 0 : 1;
    int returns = type.returnType().descriptorString().equals("V") ? 0 : 1;
    return new Effect(type.parameterCount() + receiver, returns);
  }

  private static Effect operatorEffect(OperatorInstruction operator) {
    return switch (operator.opcode()) {
      case INEG, LNEG, FNEG, DNEG, ARRAYLENGTH -> new Effect(1, 1);
      default -> new Effect(2, 1);
    };
  }
}
