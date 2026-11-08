package io.mutastic.engine;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// What kotlinc passes in a call that leaves arguments to their defaults: a call to `f$default`, or
/// to a constructor taking a `DefaultConstructorMarker`, gets a mask saying which arguments were
/// left out, and a zero or a `null` in each of their places. The callee works the defaults out
/// itself, so none of these values is read as passed: changing one asks for a test of nothing the
/// person wrote. Told by the call's descriptor, and only constants are taken.
final class KotlinDefaults {
  private static final String MARKER = "Lkotlin/jvm/internal/DefaultConstructorMarker;";
  private static final String OBJECT = "Ljava/lang/Object;";

  static final String REASON = "generated: kotlinc's stand-in for an argument left to its default";

  private KotlinDefaults() {}

  /// The places in the code of each such mask, and of each stand-in its mask marks.
  static Set<Integer> places(List<CodeElement> code) {
    var instructions = new ArrayList<Instruction>();
    var places = new ArrayList<Integer>();
    var targets = targets(code, instructions, places);
    var found = new HashSet<Integer>();
    for (int at = 0; at < instructions.size(); at++) {
      if (instructions.get(at) instanceof InvokeInstruction call && leavesDefaults(call)) {
        for (int index : standIns(instructions, at, call.typeSymbol(), targets)) {
          found.add(places.get(index));
        }
      }
    }
    return found;
  }

  private static Set<Integer> targets(
      List<CodeElement> code, List<Instruction> instructions, List<Integer> places) {
    var labels = new HashSet<Label>();
    for (CodeElement element : code) {
      switch (element) {
        case BranchInstruction branch -> labels.add(branch.target());
        case TableSwitchInstruction table -> labels.add(table.defaultTarget());
        case LookupSwitchInstruction lookup -> labels.add(lookup.defaultTarget());
        case ExceptionCatch handler -> labels.add(handler.handler());
        default -> {}
      }
    }
    var targets = new HashSet<Integer>();
    for (int at = 0; at < code.size(); at++) {
      switch (code.get(at)) {
        case LabelTarget target when labels.contains(target.label()) ->
            targets.add(instructions.size());
        case Instruction instruction -> {
          instructions.add(instruction);
          places.add(at);
        }
        default -> {}
      }
    }
    return targets;
  }

  /// A call to `f$default`, whose last two parameters are the mask and an unused `Object`, or a
  /// constructor whose last is a `DefaultConstructorMarker` after the mask.
  private static boolean leavesDefaults(InvokeInstruction call) {
    var parameters = call.typeSymbol().parameterList();
    if (parameters.size() < 2
        || !parameters.get(parameters.size() - 2).descriptorString().equals("I")) {
      return false;
    }
    var last = parameters.getLast().descriptorString();
    return (call.opcode() == Opcode.INVOKESTATIC
            && call.name().stringValue().endsWith("$default")
            && last.equals(OBJECT))
        || (call.name().equalsString("<init>") && last.equals(MARKER));
  }

  /// The instructions that push the mask, and the stand-ins for the arguments it marks.
  private static List<Integer> standIns(
      List<Instruction> instructions, int at, MethodTypeDesc type, Set<Integer> targets) {
    int count = type.parameterCount();
    var mask = StackProducers.producer(instructions, at, 2, targets);
    var bits = mask.flatMap(index -> number(instructions.get(index)));
    if (bits.isEmpty()) {
      return List.of();
    }
    var found = new ArrayList<Integer>(List.of(mask.get()));
    var call = new Call(instructions, at, count, targets);
    // A member's or an extension's receiver comes first and has no bit: the stand-ins are one on.
    for (int receivers = 0; receivers <= 1; receivers++) {
      var standIns = call.standIns(bits.get(), receivers);
      if (standIns.isPresent()) {
        found.addAll(standIns.get());
        break;
      }
    }
    return found;
  }

  /// The arguments of one call.
  private record Call(List<Instruction> instructions, int at, int count, Set<Integer> targets) {
    /// The stand-ins for each argument the mask marks, with this many receivers before them; empty
    /// unless each is a zero or a `null`.
    Optional<List<Integer>> standIns(long bits, int receivers) {
      var found = new ArrayList<Integer>();
      int arguments = count - 2 - receivers;
      for (int bit = 0; bit < Integer.SIZE; bit++) {
        if ((bits & (1L << bit)) != 0) {
          var producer =
              bit < arguments
                  ? StackProducers.producer(instructions, at, count - receivers - bit, targets)
                      .filter(index -> isZeroOrNull(instructions.get(index)))
                  : Optional.<Integer>empty();
          if (producer.isEmpty()) {
            return Optional.empty();
          }
          found.add(producer.get());
        }
      }
      return Optional.of(found);
    }
  }

  private static Optional<Long> number(Instruction instruction) {
    return instruction instanceof ConstantInstruction constant
            && constant.constantValue() instanceof Number number
        ? Optional.of(number.longValue())
        : Optional.empty();
  }

  private static boolean isZeroOrNull(Instruction instruction) {
    return instruction.opcode() == Opcode.ACONST_NULL
        || number(instruction).filter(value -> value == 0).isPresent();
  }
}
