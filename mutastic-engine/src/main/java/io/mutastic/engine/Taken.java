package io.mutastic.engine;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// A call, or javac's `invokedynamic`, that takes the value pushed at a place in the code: where
/// the value is among those it takes (`0` an instance call's receiver), where the call is, and
/// what's pushed after the value, as far as it's known, bottom first: `new
/// java/util/concurrent/LinkedBlockingQueue()V` for a queue made with no bound.
record Taken(Instruction call, int position, int index, List<String> after) {
  String owner() {
    return call instanceof InvokeInstruction invoke ? invoke.owner().asInternalName() : "";
  }

  String name() {
    return switch (call) {
      case InvokeInstruction invoke -> invoke.name().stringValue();
      case InvokeDynamicInstruction indy -> indy.bootstrapMethod().methodName();
      default -> "";
    };
  }

  /// The type of the parameter the value is, if the value isn't the receiver.
  Optional<String> parameter() {
    if (!(call instanceof InvokeInstruction invoke)) {
      return Optional.empty();
    }
    int index = position - (invoke.opcode() == Opcode.INVOKESTATIC ? 0 : 1);
    var type = invoke.typeSymbol();
    return index >= 0 && index < type.parameterCount()
        ? Optional.of(type.parameterType(index).descriptorString())
        : Optional.empty();
  }

  boolean is(String owner, String name, int position) {
    return owner().equals(owner) && name().equals(name) && this.position == position;
  }

  /// Follows the value at `code[at]` along straight code to the call that takes it; none if a label
  /// comes first, or an instruction that does anything else with it.
  static Optional<Taken> of(List<CodeElement> code, int at) {
    var after = new ArrayList<String>();
    for (int index = at + 1; index < code.size(); index++) {
      switch (code.get(index)) {
        case LabelTarget _ -> {
          return Optional.empty();
        }
        case Instruction instruction when takes(instruction) >= 0 -> {
          int takes = takes(instruction);
          if (takes > after.size()) {
            return Optional.of(
                new Taken(instruction, takes - after.size() - 1, index, List.copyOf(after)));
          }
          leftBy(instruction, takes, after);
        }
        case Instruction instruction -> {
          if (!followed(instruction, after)) {
            return Optional.empty();
          }
        }
        default -> {}
      }
    }
    return Optional.empty();
  }

  /// The values a call takes off the stack, its receiver included; `-1` for what isn't a call.
  private static int takes(Instruction instruction) {
    return switch (instruction) {
      case InvokeInstruction call ->
          call.typeSymbol().parameterCount() + (call.opcode() == Opcode.INVOKESTATIC ? 0 : 1);
      case InvokeDynamicInstruction indy -> indy.typeSymbol().parameterCount();
      default -> -1;
    };
  }

  /// A call that takes only values pushed after ours: what it leaves in their place. A constructor
  /// leaves the object `new` made, which says how: `new X()V` for one given nothing.
  private static void leftBy(Instruction instruction, int takes, List<String> after) {
    var taken = after.subList(after.size() - takes, after.size());
    var receiver = taken.isEmpty() ? "" : taken.getFirst();
    taken.clear();
    MethodTypeDesc type =
        switch (instruction) {
          case InvokeInstruction call -> call.typeSymbol();
          case InvokeDynamicInstruction indy -> indy.typeSymbol();
          default -> throw new IllegalArgumentException("not a call: " + instruction);
        };
    if (instruction instanceof InvokeInstruction call && call.name().equalsString("<init>")) {
      var made = "new " + call.owner().asInternalName();
      if (receiver.equals(made) && !after.isEmpty() && after.getLast().equals(made)) {
        after.set(after.size() - 1, made + type.descriptorString());
      }
    } else if (!type.returnType().equals(ConstantDescs.CD_void)) {
      after.add("?");
    }
  }

  /// What an instruction that isn't a call does to the values pushed after ours; false if it does
  /// anything with ours but widen it, or this doesn't follow it. A `pop` is javac's after the null
  /// check of `service::fetch`'s `service`, dropping what the check gives back.
  private static boolean followed(Instruction instruction, List<String> after) {
    boolean ours = after.isEmpty();
    switch (instruction) {
      case LoadInstruction _, ConstantInstruction _ -> after.add("?");
      case FieldInstruction field when field.opcode() == Opcode.GETSTATIC -> after.add("?");
      case NewObjectInstruction made -> after.add("new " + made.className().asInternalName());
      case StackInstruction dup when dup.opcode() == Opcode.DUP && !ours ->
          after.add(after.getLast());
      case StackInstruction pop when pop.opcode() == Opcode.POP && !ours -> after.removeLast();
      case Instruction same when ours -> {
        return same.opcode() == Opcode.I2L || same.opcode() == Opcode.CHECKCAST;
      }
      case Instruction top when !ours && replacesTheTop(top) -> after.set(after.size() - 1, "?");
      case ArrayStoreInstruction _ when after.size() >= 3 -> {
        after.subList(after.size() - 3, after.size()).clear();
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  /// An instruction that takes the value on top and leaves one in its place: a field read from an
  /// object, a conversion, a cast or a check of its type, a new array of that length.
  private static boolean replacesTheTop(Instruction instruction) {
    return switch (instruction) {
      case FieldInstruction field -> field.opcode() == Opcode.GETFIELD;
      case ConvertInstruction _, TypeCheckInstruction _, NewReferenceArrayInstruction _ -> true;
      default -> false;
    };
  }
}
