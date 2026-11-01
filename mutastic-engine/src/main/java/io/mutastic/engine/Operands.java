package io.mutastic.engine;

import java.lang.classfile.Attributes;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/// What a comparison or calculation works on, as the source names it: `total` and `100` in `total
/// >= 100`, `element.length()` and `2` in `element.length() < 2`, read back from the instructions
/// just before it. A branch on a `boolean` call gives one operand, the call:
/// `Files.isRegularFile(git)`. Empty when the code is more involved than that.
///
/// Fed every instruction in order, and told when a jump target is passed: what came before a jump
/// target isn't the only way in, so it doesn't count.
final class Operands {
  private record Local(int slot, String name, int from, int to) {}

  /// One value on the stack and where its instructions start; `start` is -1 when unknown.
  private record Operand(String text, int start, boolean predicate) {}

  private final List<Local> locals = new ArrayList<>();
  private final IdentityHashMap<Instruction, Integer> positions = new IdentityHashMap<>();
  private final List<Instruction> recent = new ArrayList<>();
  private int position;

  Operands(CodeModel code) {
    code.findAttribute(Attributes.localVariableTable())
        .ifPresent(
            table -> {
              for (var variable : table.localVariables()) {
                locals.add(
                    new Local(
                        variable.slot(),
                        variable.name().stringValue(),
                        variable.startPc(),
                        variable.startPc() + variable.length()));
              }
            });
  }

  /// A jump lands here.
  void jumpTarget() {
    recent.clear();
  }

  /// What `instruction` works on, before adding it to what's been seen.
  List<String> of(Instruction instruction) {
    var operands = operands(instruction);
    positions.put(instruction, position);
    position += instruction.sizeInBytes();
    recent.add(instruction);
    return operands;
  }

  private List<String> operands(Instruction instruction) {
    var before = recent;
    // `from > 0` on longs is LCMP then IFLE: the operands are the two values before LCMP.
    boolean afterCompare = !before.isEmpty() && isCompare(before.getLast());
    if (afterCompare) {
      before = before.subList(0, before.size() - 1);
    }
    int wanted =
        switch (instruction) {
          case BranchInstruction branch when branch.opcode().name().startsWith("IF_ICMP") -> 2;
          case BranchInstruction _ when afterCompare -> 2;
          case BranchInstruction branch
              when branch.opcode() == Opcode.IFNULL || branch.opcode() == Opcode.IFNONNULL ->
              0;
          case BranchInstruction branch when branch.opcode().name().startsWith("IF_ACMP") -> 0;
          case BranchInstruction branch when branch.opcode() == Opcode.GOTO -> 0;
          case BranchInstruction _ -> 1; // compares with zero
          case OperatorInstruction _ -> 2;
          default -> 0;
        };
    var names = new ArrayList<String>();
    int end = before.size();
    Operand operand = null;
    for (int i = 0; i < wanted; i++) {
      operand = endingAt(before, end);
      if (operand == null || (operand.start() < 0 && i < wanted - 1)) {
        return List.of();
      }
      names.addFirst(operand.text());
      end = operand.start();
    }
    if (wanted == 1 && !operand.predicate()) {
      names.add("0");
    }
    return names;
  }

  private Operand endingAt(List<Instruction> before, int end) {
    if (end <= 0) {
      return null;
    }
    return switch (before.get(end - 1)) {
      case LoadInstruction load -> new Operand(nameOf(load), end - 1, false);
      case ConstantInstruction constant ->
          new Operand(literal(constant.constantValue()), end - 1, false);
      // `price * quantity` with a long and an int: javac widens `quantity` first. Still `quantity`.
      case ConvertInstruction _ -> endingAt(before, end - 1);
      case InvokeInstruction call -> call(before, end, call);
      // An empty varargs array (`Files.isRegularFile(git)` passes `new LinkOption[0]`): no
      // argument.
      case NewReferenceArrayInstruction _
          when end >= 2
              && before.get(end - 2) instanceof ConstantInstruction size
              && Integer.valueOf(0).equals(size.constantValue()) ->
          new Operand("", end - 2, false);
      default -> null;
    };
  }

  /// `Files.isRegularFile(git)`, `head.startsWith("ref: ")`, or `startsWith(…)` when its arguments
  /// or receiver are more than a load or a constant.
  private Operand call(List<Instruction> before, int end, InvokeInstruction call) {
    var type = call.typeSymbol();
    boolean predicate = type.returnType().descriptorString().equals("Z");
    var name = call.name().stringValue();
    var arguments = new ArrayList<String>();
    int at = end - 1;
    for (int i = 0; i < type.parameterCount(); i++) {
      var argument = endingAt(before, at);
      if (argument == null || argument.start() < 0) {
        return new Operand(name + "(…)", -1, predicate);
      }
      if (!argument.text().isEmpty()) {
        arguments.addFirst(argument.text());
      }
      at = argument.start();
    }
    var list = "(" + String.join(", ", arguments) + ")";
    if (call.opcode() == Opcode.INVOKESTATIC) {
      return new Operand(call.owner().asSymbol().displayName() + "." + name + list, at, predicate);
    }
    var receiver = endingAt(before, at);
    // A local with no name is javac's own, like a for-each loop's iterator: leave it out.
    return receiver == null || receiver.start() < 0 || receiver.text().startsWith("local")
        ? new Operand(name + list, -1, predicate)
        : new Operand(receiver.text() + "." + name + list, receiver.start(), predicate);
  }

  /// The source name for a local, by where it's read: javac reuses a slot for variables in
  /// different scopes, so the name depends on the position.
  private String nameOf(LoadInstruction load) {
    int at = positions.getOrDefault(load, -1);
    return locals.stream()
        .filter(local -> local.slot() == load.slot() && local.from() <= at && at < local.to())
        .map(Local::name)
        .findFirst()
        .orElse("local" + load.slot());
  }

  private static String literal(Object value) {
    return value instanceof String text ? "\"" + text + "\"" : String.valueOf(value);
  }

  private static boolean isCompare(Instruction instruction) {
    return switch (instruction.opcode()) {
      case LCMP, FCMPL, FCMPG, DCMPL, DCMPG -> true;
      default -> false;
    };
  }
}
