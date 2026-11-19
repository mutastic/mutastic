package io.mutastic.engine;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.util.List;
import java.util.Optional;

/// [io.mutastic.api.BuiltIn#BOOLEAN_CONSTANT]: a `true` or `false` the code passes to a method, or
/// stores in a field or a local `boolean`, the other way round. A returned one is return value's.
///
/// In the bytecode a `boolean` is an `int`, so what takes the `0` or `1` decides: a parameter, a
/// field or a variable declared `boolean`. The value is followed to what takes it in a straight
/// line, with no jump in between, so the `0` and `1` javac writes for `a > b` as a value, one on
/// each side of a jump, are never taken for the source's.
final class BooleanConstants {
  private BooleanConstants() {}

  /// What the constant at `code[at]` becomes, `constant true to false`, if it's a `boolean` passed
  /// or stored: with a reason not to run it if it's a variable's value that nothing reads.
  static Optional<io.mutastic.api.Mutator.Change> change(
      List<CodeElement> code, int at, Stores stores) {
    if (!(code.get(at) instanceof ConstantInstruction constant)
        || (constant.opcode() != Opcode.ICONST_0 && constant.opcode() != Opcode.ICONST_1)) {
      return Optional.empty();
    }
    var label =
        constant.opcode() == Opcode.ICONST_1 ? "constant true to false" : "constant false to true";
    return takenAsBoolean(code, at, stores)
        .map(
            store ->
                new io.mutastic.api.Mutator.Change(
                    label,
                    store >= 0 && !stores.valueIsRead(store)
                        ? Optional.of("equivalent: nothing reads the value it's given")
                        : Optional.empty()));
  }

  /// Where the constant at `code[at]` is taken as a `boolean`, if it is: the store's place for a
  /// variable, so whether it's read can be asked, and `-1` for an argument or a field. The value is
  /// followed down the stack, in values, to what takes it off.
  private static Optional<Integer> takenAsBoolean(List<CodeElement> code, int at, Stores stores) {
    int depth = 1;
    for (int index = at + 1; index < code.size(); index++) {
      var element = code.get(index);
      if (element instanceof LabelTarget) {
        return Optional.empty(); // other code can jump in, and leave something else
      }
      if (!(element instanceof Instruction instruction)) {
        continue;
      }
      var effect = effect(instruction);
      if (effect.isEmpty() || ends(instruction)) {
        return Optional.empty();
      }
      if (effect.get().pops() >= depth) {
        return takesAsBoolean(instruction, depth, index, stores)
            ? Optional.of(instruction instanceof StoreInstruction ? index : -1)
            : Optional.empty();
      }
      depth += effect.get().pushes() - effect.get().pops();
    }
    return Optional.empty();
  }

  /// What `instruction` takes off the stack and puts on, in values; a store's too.
  private static Optional<StackProducers.Effect> effect(Instruction instruction) {
    return switch (instruction) {
      case StoreInstruction _ -> Optional.of(new StackProducers.Effect(1, 0));
      case ArrayStoreInstruction _ -> Optional.of(new StackProducers.Effect(3, 0));
      default -> StackProducers.effect(instruction);
    };
  }

  /// A jump, a return or a throw: past it, nothing in a straight line takes the value.
  private static boolean ends(Instruction instruction) {
    return instruction instanceof BranchInstruction
        || instruction instanceof ReturnInstruction
        || instruction instanceof ThrowInstruction;
  }

  /// Whether `instruction` takes the value `depth` places under the top as a `boolean`: a
  /// parameter, a field or a source variable of that type.
  private static boolean takesAsBoolean(Instruction instruction, int depth, int at, Stores stores) {
    return switch (instruction) {
      case InvokeInstruction call -> {
        var parameters = call.typeSymbol().parameterList();
        yield depth <= parameters.size()
            && parameters.get(parameters.size() - depth).descriptorString().equals("Z");
      }
      case FieldInstruction field ->
          depth == 1
              && (field.opcode() == Opcode.PUTFIELD || field.opcode() == Opcode.PUTSTATIC)
              && field.type().equalsString("Z");
      case StoreInstruction store ->
          store.typeKind() == java.lang.classfile.TypeKind.INT
              && stores
                  .storedVariable(at, store.slot())
                  .filter(local -> local.type().equalsString("Z"))
                  .isPresent();
      default -> false;
    };
  }

  /// The other `boolean` in its place.
  static void write(CodeBuilder builder, ConstantInstruction constant) {
    if (constant.opcode() == Opcode.ICONST_1) {
      builder.iconst_0();
    } else {
      builder.iconst_1();
    }
  }
}
