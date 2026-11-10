package io.mutastic.engine;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.instruction.InvokeInstruction;

/// `Math.addExact` and the like as the plain operation, on what's already on the stack. The rule
/// that uses this lists the overloads: on any other method, there's nothing to write, and the
/// mutant is skipped as unviable.
final class UncheckedArithmetic {
  private UncheckedArithmetic() {}

  static void write(CodeBuilder builder, InvokeInstruction call) {
    var name = call.name().stringValue();
    var shape = call.type().stringValue();
    switch (name + shape) {
      case "addExact(II)I" -> builder.iadd();
      case "addExact(JJ)J" -> builder.ladd();
      case "subtractExact(II)I" -> builder.isub();
      case "subtractExact(JJ)J" -> builder.lsub();
      case "multiplyExact(II)I" -> builder.imul();
      case "multiplyExact(JJ)J" -> builder.lmul();
      case "multiplyExact(JI)J" -> builder.i2l().lmul();
      case "incrementExact(I)I" -> builder.iconst_1().iadd();
      case "incrementExact(J)J" -> builder.lconst_1().ladd();
      case "decrementExact(I)I" -> builder.iconst_1().isub();
      case "decrementExact(J)J" -> builder.lconst_1().lsub();
      case "negateExact(I)I" -> builder.ineg();
      case "negateExact(J)J" -> builder.lneg();
      case "toIntExact(J)I" -> builder.l2i();
      default -> throw new IllegalArgumentException("no plain form of " + name + shape);
    }
  }

  /// The plain operation as the source would write it, on the call's arguments: `{arg1} + {arg2}`
  /// for `addExact`. See [io.mutastic.api.Mutator.Words#written].
  static String written(String name, String descriptor) {
    var one = descriptor.endsWith(")J") ? "1L" : "1";
    return switch (name) {
      case "addExact" -> "{arg1} + {arg2}";
      case "subtractExact" -> "{arg1} - {arg2}";
      case "multiplyExact" -> "{arg1} * {arg2}";
      case "incrementExact" -> "{arg1} + " + one;
      case "decrementExact" -> "{arg1} - " + one;
      case "negateExact" -> "-{arg1}";
      case "toIntExact" -> "(int) {arg1}";
      default -> throw new IllegalArgumentException("no plain form of " + name);
    };
  }
}
