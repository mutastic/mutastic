package io.mutastic.engine;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

/// [io.mutastic.api.BuiltIn#DROP_VARARG]: a call whose last argument is an array built for it on
/// the spot, `of(a, b, c)` or `of(new T[] {a, b, c})`, gets it without its last element. It's
/// copied just before the call, so every element is still worked out, and the array keeps its type.
final class DroppedElement {
  static final String DROP = "drop last element";

  private DroppedElement() {}

  /// Whether the call at `code[at]` takes an array that's just had an element stored in it, so it
  /// has at least one. `f(values[i] = other)` also stores just before the call, but it passes the
  /// value stored: its `dup_x2` shows that, and it could be empty.
  static boolean at(List<CodeElement> code, int at) {
    if (!(code.get(at) instanceof InvokeInstruction call)) {
      return false;
    }
    var parameters = call.typeSymbol().parameterList();
    if (parameters.isEmpty() || !parameters.getLast().isArray()) {
      return false;
    }
    var before = ImpliedOr.instructionsBefore(code, at, 2);
    return before.size() == 2
        && before.get(1) instanceof ArrayStoreInstruction
        && !isDupBelow(before.get(0));
  }

  private static boolean isDupBelow(Instruction instruction) {
    return instruction.opcode() == Opcode.DUP_X2 || instruction.opcode() == Opcode.DUP2_X2;
  }

  /// `Arrays.copyOf(array, array.length - 1)` on top of the stack, then the call.
  static void write(CodeBuilder builder, InvokeInstruction call) {
    var array = call.typeSymbol().parameterList().getLast();
    boolean references = !array.componentType().isPrimitive();
    var copied = references ? ConstantDescs.CD_Object.arrayType() : array;
    builder.dup().arraylength().iconst_1().isub();
    builder.invokestatic(
        ClassDesc.of("java.util.Arrays"),
        "copyOf",
        MethodTypeDesc.of(copied, copied, ConstantDescs.CD_int));
    if (references) {
      builder.checkcast(array);
    }
    builder.with(call);
  }
}
