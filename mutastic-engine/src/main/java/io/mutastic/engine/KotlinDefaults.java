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

  /// The instructions that push the masks, and the stand-ins for the arguments they mark. A mask
  /// has a bit for each of 32 arguments, so a call with more has a mask for every 32, in order,
  /// before the last parameter.
  private static List<Integer> standIns(
      List<Instruction> instructions, int at, MethodTypeDesc type, Set<Integer> targets) {
    int count = type.parameterCount();
    List<Integer> masksOnly = List.of();
    // A member's or an extension's receiver comes first and has no bit: the stand-ins are one on.
    for (int receivers = 0; receivers <= 1; receivers++) {
      int masks = masks(type, receivers);
      if (masks == 0) {
        continue;
      }
      var call = new Call(instructions, at, count, receivers, masks, targets);
      var words = call.words();
      if (words.isEmpty()) {
        continue;
      }
      var standIns = call.standIns(words.get().bits());
      if (standIns.isPresent()) {
        var found = new ArrayList<>(words.get().places());
        found.addAll(standIns.get());
        return found;
      }
      if (masksOnly.isEmpty()) {
        masksOnly = words.get().places();
      }
    }
    return masksOnly;
  }

  /// How many masks a call takes with this many receivers: `n` arguments that may be left out have
  /// a mask for every 32 of them, all `int`s. None if the parameters can't be read that way.
  static int masks(MethodTypeDesc type, int receivers) {
    var parameters = type.parameterList();
    int argumentsAndMasks = parameters.size() - 1 - receivers;
    for (int masks = 1; masks <= argumentsAndMasks; masks++) {
      int arguments = argumentsAndMasks - masks;
      if (arguments > 0 && (arguments + Integer.SIZE - 1) / Integer.SIZE == masks) {
        boolean ints =
            parameters.subList(parameters.size() - 1 - masks, parameters.size() - 1).stream()
                .allMatch(parameter -> parameter.descriptorString().equals("I"));
        return ints ? masks : 0;
      }
    }
    return 0;
  }

  /// The masks' values, and the instructions that push them.
  private record Words(List<Long> bits, List<Integer> places) {}

  /// The arguments of one call, with its receivers and its masks.
  private record Call(
      List<Instruction> instructions,
      int at,
      int count,
      int receivers,
      int masks,
      Set<Integer> targets) {
    /// Each mask, when every one is a constant.
    Optional<Words> words() {
      var bits = new ArrayList<Long>();
      var places = new ArrayList<Integer>();
      for (int word = 0; word < masks; word++) {
        var mask = StackProducers.producer(instructions, at, 1 + masks - word, targets);
        var value = mask.flatMap(index -> number(instructions.get(index)));
        if (value.isEmpty()) {
          return Optional.empty();
        }
        bits.add(value.get());
        places.add(mask.get());
      }
      return Optional.of(new Words(bits, places));
    }

    /// The stand-ins for each argument the masks mark; empty unless each is a zero or a `null`.
    Optional<List<Integer>> standIns(List<Long> bits) {
      var found = new ArrayList<Integer>();
      int arguments = count - 1 - masks - receivers;
      for (int word = 0; word < masks; word++) {
        for (int bit = 0; bit < Integer.SIZE; bit++) {
          if ((bits.get(word) & (1L << bit)) == 0) {
            continue;
          }
          int argument = word * Integer.SIZE + bit;
          var producer =
              argument < arguments
                  ? StackProducers.producer(instructions, at, count - receivers - argument, targets)
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
