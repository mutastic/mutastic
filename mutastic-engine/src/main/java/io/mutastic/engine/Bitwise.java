package io.mutastic.engine;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// [io.mutastic.api.BuiltIn#BITWISE]: what each bitwise operator becomes, as the Rust edition's
/// `mutastic/bitwise` makes it. `&`, `|` and `^` each become the other two, `<<` and `>>` swap, and
/// so do `>>` and `>>>`, on `int` and `long` (and on `boolean`, whose `&`, `|` and `^` are the same
/// instructions).
final class Bitwise {
  private static final Map<String, List<String>> CHANGES =
      Map.of(
          "AND", List.of("OR", "XOR"),
          "OR", List.of("AND", "XOR"),
          "XOR", List.of("AND", "OR"),
          "SHL", List.of("SHR"),
          "SHR", List.of("SHL", "USHR"),
          "USHR", List.of("SHR"));

  private Bitwise() {}

  /// The opcodes the instruction at `code[at]` can become, by name: `IOR`. None for `~x`, which
  /// javac writes as `x ^ -1`: as `x & -1` it's `x`, and as `x | -1` it's `-1`, neither of them a
  /// mistake anyone makes in writing `~`. And as in Rust, `a &= b` and `a |= b` never become `^=`:
  /// setting or clearing bits isn't flipping them.
  static List<String> changes(List<CodeElement> code, int at) {
    var opcode = ((Instruction) code.get(at)).opcode();
    var type = opcode.name().substring(0, 1);
    var operation = opcode.name().substring(1);
    if (!(type.equals("I") || type.equals("L"))
        || !CHANGES.containsKey(operation)
        || (operation.equals("XOR") && isNot(code, at))) {
      return List.of();
    }
    boolean compound = isCompound(code, at);
    return CHANGES.get(operation).stream()
        .filter(other -> !(compound && other.equals("XOR")))
        .map(other -> type + other)
        .toList();
  }

  /// `x ^ -1` or `-1 ^ x`, as javac writes `~x`.
  private static boolean isNot(List<CodeElement> code, int at) {
    return operand(code, at, 1).filter(Bitwise::isMinusOne).isPresent()
        || operand(code, at, 2).filter(Bitwise::isMinusOne).isPresent();
  }

  private static boolean isMinusOne(Instruction instruction) {
    return instruction instanceof ConstantInstruction constant
        && constant.constantValue() instanceof Number value
        && (value instanceof Integer || value instanceof Long)
        && value.longValue() == -1;
  }

  /// `a = a & b`, as `a &= b` compiles: the result stored where its first value was read from, a
  /// local variable or a field.
  private static boolean isCompound(List<CodeElement> code, int at) {
    var after = SiteSkips.instructionsAfter(code, at, 1);
    var first = operand(code, at, 2);
    if (after.isEmpty() || first.isEmpty()) {
      return false;
    }
    var stored = after.getFirst();
    return switch (first.get()) {
      case LoadInstruction load ->
          stored instanceof StoreInstruction store && store.slot() == load.slot();
      case FieldInstruction read ->
          stored instanceof FieldInstruction write && sameField(read, write);
      default -> false;
    };
  }

  private static boolean sameField(FieldInstruction read, FieldInstruction write) {
    boolean paired =
        (read.opcode() == Opcode.GETFIELD && write.opcode() == Opcode.PUTFIELD)
            || (read.opcode() == Opcode.GETSTATIC && write.opcode() == Opcode.PUTSTATIC);
    return paired && read.owner().equals(write.owner()) && read.name().equals(write.name());
  }

  /// The instruction that pushed the value `below` places under the top before `code[at]`: 1 is the
  /// second value, 2 the first. Empty past a label, where other code can jump in, or where the code
  /// is more than [StackProducers] follows.
  private static Optional<Instruction> operand(List<CodeElement> code, int at, int below) {
    int depth = below;
    for (int index = at - 1; index >= 0; index--) {
      var element = code.get(index);
      if (element instanceof LabelTarget) {
        return Optional.empty();
      }
      if (element instanceof Instruction instruction) {
        var effect = StackProducers.effect(instruction);
        if (effect.isEmpty()) {
          return Optional.empty();
        }
        if (depth <= effect.get().pushes()) {
          return effect.get().pushes() == 1 ? Optional.of(instruction) : Optional.empty();
        }
        depth = depth - effect.get().pushes() + effect.get().pops();
      }
    }
    return Optional.empty();
  }
}
