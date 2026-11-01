package io.mutastic.engine;

import io.mutastic.api.Filter;
import io.mutastic.api.Skip;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/// A method that only reads or writes one of its class's fields is arid: no test of it is worth
/// writing, and a field named wrong is a lint's to find. Decided by exact bytecode shape, labels
/// and line numbers aside, so any logic (a copy, a check, lazy set-up, unboxing) means it's not
/// trivial and is mutated. The name must be the field's: `getX`, `isX`, `setX`, `withX`, or the
/// field's own name, as a record or fluent accessor has.
///
/// - getter: `aload_0; getfield f; return f`, or `getstatic f; return f` when static
/// - setter: `aload_0; load the argument; putfield f; return`, or `return this` when fluent, or
///   `load; putstatic f; return` when static
final class TrivialAccessors implements Filter {
  @Override
  public String id() {
    return "mutastic:trivial-accessors";
  }

  @Override
  public Optional<Skip> skip(ClassModel owner, MethodModel method) {
    var code = method.code();
    if (code.isEmpty() || Annotations.has(owner, method, "DoMutate")) {
      return Optional.empty();
    }
    var instructions =
        code.get().elementList().stream()
            .filter(Instruction.class::isInstance)
            .map(Instruction.class::cast)
            .toList();
    var name = method.methodName().stringValue();
    return getter(owner, method, instructions)
        .or(() -> setter(owner, method, instructions))
        .filter(field -> named(name, field))
        .map(field -> new Skip(Skip.Kind.ARID, kind(instructions) + " of " + field));
  }

  /// The field a getter returns as it is.
  private static Optional<String> getter(
      ClassModel owner, MethodModel method, List<Instruction> instructions) {
    boolean isStatic = method.flags().has(java.lang.reflect.AccessFlag.STATIC);
    var type = MethodTypeDesc.ofDescriptor(method.methodType().stringValue());
    var expected = isStatic ? List.of(Opcode.GETSTATIC) : List.of(Opcode.ALOAD_0, Opcode.GETFIELD);
    if (type.parameterCount() != 0 || !startsWith(instructions, expected, 1)) {
      return Optional.empty();
    }
    var field = (FieldInstruction) instructions.get(expected.size() - 1);
    return ownField(owner, field)
            && field.typeSymbol().equals(type.returnType())
            && instructions.getLast() instanceof ReturnInstruction
        ? Optional.of(field.name().stringValue())
        : Optional.empty();
  }

  /// The field a setter stores its argument in, unchanged.
  private static Optional<String> setter(
      ClassModel owner, MethodModel method, List<Instruction> instructions) {
    boolean isStatic = method.flags().has(java.lang.reflect.AccessFlag.STATIC);
    var type = MethodTypeDesc.ofDescriptor(method.methodType().stringValue());
    int store = isStatic ? 1 : 2;
    if (type.parameterCount() != 1 || instructions.size() < store + 2) {
      return Optional.empty();
    }
    var rest = instructions.subList(store + 1, instructions.size());
    return storedArgument(owner, instructions, isStatic)
        .filter(field -> field.typeSymbol().equals(type.parameterList().getFirst()))
        .filter(field -> returnsNothingOrThis(rest, owner, type))
        .map(field -> field.name().stringValue());
  }

  /// The own field the argument goes into: `aload_0; load 1; putfield`, or `load 0; putstatic`.
  private static Optional<FieldInstruction> storedArgument(
      ClassModel owner, List<Instruction> instructions, boolean isStatic) {
    int argument = isStatic ? 0 : 1;
    boolean onThis = isStatic || instructions.getFirst().opcode() == Opcode.ALOAD_0;
    boolean loadsArgument =
        instructions.get(argument) instanceof LoadInstruction load && load.slot() == argument;
    return onThis
            && loadsArgument
            && instructions.get(argument + 1) instanceof FieldInstruction field
            && field.opcode() == (isStatic ? Opcode.PUTSTATIC : Opcode.PUTFIELD)
            && ownField(owner, field)
        ? Optional.of(field)
        : Optional.empty();
  }

  private static boolean returnsNothingOrThis(
      List<Instruction> rest, ClassModel owner, MethodTypeDesc type) {
    if (rest.size() == 1) {
      return rest.getFirst().opcode() == Opcode.RETURN;
    }
    return rest.size() == 2
        && rest.get(0).opcode() == Opcode.ALOAD_0
        && rest.get(1).opcode() == Opcode.ARETURN
        && type.returnType().equals(owner.thisClass().asSymbol());
  }

  /// The instructions are `expected` and then exactly `more` others.
  private static boolean startsWith(
      List<Instruction> instructions, List<Opcode> expected, int more) {
    if (instructions.size() != expected.size() + more) {
      return false;
    }
    for (int at = 0; at < expected.size(); at++) {
      if (instructions.get(at).opcode() != expected.get(at)) {
        return false;
      }
    }
    return true;
  }

  private static boolean ownField(ClassModel owner, FieldInstruction field) {
    return field.owner().equals(owner.thisClass());
  }

  /// `getX`, `isX`, `setX`, `withX` for field `x`, or the field's own name.
  private static boolean named(String method, String field) {
    var capitalised = field.substring(0, 1).toUpperCase(Locale.ROOT) + field.substring(1);
    return method.equals(field)
        || List.of("get", "is", "set", "with").stream()
            .anyMatch(prefix -> method.equals(prefix + capitalised));
  }

  private static String kind(List<Instruction> instructions) {
    return instructions.stream()
            .anyMatch(each -> each.opcode() == Opcode.PUTFIELD || each.opcode() == Opcode.PUTSTATIC)
        ? "trivial setter"
        : "trivial getter";
  }
}
