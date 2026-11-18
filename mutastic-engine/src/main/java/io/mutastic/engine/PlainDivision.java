package io.mutastic.engine;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.util.List;
import java.util.Optional;

/// `Math.floorDiv` and `Math.floorMod` as `/` and `%`, on what's already on the stack. They differ
/// only when the two numbers have opposite signs and don't divide exactly, so where neither can be
/// negative the change is skipped as equivalent. The rule that uses this lists the overloads: on
/// any other method, there's nothing to write, and the mutant is skipped as unviable.
final class PlainDivision {
  static final String EQUIVALENT =
      "equivalent: `%s` rounds differently from `%s` only for a negative number. That can't happen"
          + " because it divides a length or count by a constant that isn't negative.";

  private PlainDivision() {}

  static void write(CodeBuilder builder, InvokeInstruction call) {
    var name = call.name().stringValue();
    var shape = call.type().stringValue();
    switch (name + shape) {
      case "floorDiv(II)I" -> builder.idiv();
      case "floorDiv(JJ)J" -> builder.ldiv();
      case "floorDiv(JI)J" -> builder.i2l().ldiv();
      case "floorMod(II)I" -> builder.irem();
      case "floorMod(JJ)J" -> builder.lrem();
      case "floorMod(JI)I" -> builder.i2l().lrem().l2i();
      default -> throw new IllegalArgumentException("no plain form of " + name + shape);
    }
  }

  /// The plain operation as the source would write it, on the call's arguments: `{arg1} / {arg2}`
  /// for `floorDiv`. See [io.mutastic.api.Mutator.Words#written].
  static String written(String name, String descriptor) {
    var operator = name.equals("floorDiv") ? "/" : "%";
    var plain = "{arg1} " + operator + " {arg2}";
    return descriptor.endsWith("(JI)I") ? "(int) (" + plain + ")" : plain;
  }

  /// Why the call at `code[at]` divides the same either way, if it does: its divisor is a constant
  /// of 0 or more, pushed just before it, and what it divides is a length or count no other code
  /// answers, worked out just before that ([Comparisons#neverNegative]).
  static Optional<String> equivalent(InvokeInstruction call, List<CodeElement> code, int at) {
    var before = instructionsBefore(code, at);
    if (before.size() < 2
        || !(before.get(0) instanceof ConstantInstruction divisor)
        || !(divisor.constantValue() instanceof Number number)
        || number.longValue() < 0
        || !Comparisons.neverNegative(before.get(1))) {
      return Optional.empty();
    }
    var name = call.name().stringValue();
    return Optional.of(EQUIVALENT.formatted(name, name.equals("floorDiv") ? "/" : "%"));
  }

  /// The two instructions before `code[at]`, nearest first, stopping at a label: past one, other
  /// code can jump in.
  private static List<Instruction> instructionsBefore(List<CodeElement> code, int at) {
    var found = new java.util.ArrayList<Instruction>();
    for (int index = at - 1; index >= 0 && found.size() < 2; index--) {
      if (code.get(index) instanceof LabelTarget) {
        break;
      }
      if (code.get(index) instanceof Instruction instruction) {
        found.add(instruction);
      }
    }
    return found;
  }
}
