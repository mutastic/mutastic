package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Operator;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Mutants judged by the code right around their instruction, on positive evidence in the bytecode:
///
/// - **arid:** arithmetic whose result only sizes a collection's first allocation (`new
///   ArrayList<>(n * 2)`) or sets how long to wait (`Thread.sleep(seconds * 1000)`), and a removed
///   call that only waits. What a test sees doesn't change, only how fast it runs.
/// - **equivalent:** a boundary where both sides give the same value at the edge: `if (x > max) max
///   = x;` stores the value `max` already holds when they're equal, and `a < b ? a : b` gives
///   either, as they're the same.
final class SiteSkips {
  /// Classes whose `int` constructor argument is only the capacity to start with.
  private static final Set<String> SIZED =
      Set.of(
          "java/util/ArrayList",
          "java/util/HashMap",
          "java/util/HashSet",
          "java/util/LinkedHashMap",
          "java/util/LinkedHashSet",
          "java/util/ArrayDeque",
          "java/util/Vector",
          "java/util/IdentityHashMap",
          "java/util/concurrent/ConcurrentHashMap",
          "java/lang/StringBuilder",
          "java/lang/StringBuffer",
          "java/io/ByteArrayOutputStream");

  private SiteSkips() {}

  /// Why the mutant `operator` makes at `code[at]` shouldn't run, if it shouldn't.
  static Optional<String> reason(Operator operator, List<CodeElement> code, int at) {
    if (operator == BuiltIn.MATH || operator == BuiltIn.ARITHMETIC_REPLACEMENT) {
      return next(code, at).flatMap(SiteSkips::onlySizesOrWaits);
    }
    if (operator == BuiltIn.REMOVE_VOID_CALL
        && code.get(at) instanceof InvokeInstruction call
        && waits(call)) {
      return Optional.of("arid: only waits");
    }
    if (operator == BuiltIn.BOUNDARY && sameValueAtTheEdge(code, at)) {
      return Optional.of("equivalent: both sides give the same value at the edge");
    }
    return Optional.empty();
  }

  /// The arithmetic's result goes straight to a capacity or a wait: next is the call, or a widening
  /// to `long` and then the call.
  private static Optional<String> onlySizesOrWaits(Instruction next) {
    if (next instanceof InvokeInstruction call
        && call.name().equalsString("<init>")
        && call.typeSymbol().descriptorString().equals("(I)V")
        && SIZED.contains(call.owner().asInternalName())) {
      return Optional.of("arid: only sizes a collection's first allocation");
    }
    if (next instanceof InvokeInstruction call
        && call.name().equalsString("ensureCapacity")
        && SIZED.contains(call.owner().asInternalName())) {
      return Optional.of("arid: only sizes a collection's first allocation");
    }
    if (next instanceof InvokeInstruction call && waits(call)) {
      return Optional.of("arid: only sets how long to wait");
    }
    return Optional.empty();
  }

  /// `Thread.sleep`, `TimeUnit.sleep`, or `Thread.join` with a time limit.
  private static boolean waits(InvokeInstruction call) {
    var owner = call.owner().asInternalName();
    var name = call.name().stringValue();
    return (owner.equals("java/lang/Thread")
            && (name.equals("sleep") || name.equals("join"))
            && call.typeSymbol().parameterCount() > 0)
        || (owner.equals("java/util/concurrent/TimeUnit") && name.equals("sleep"));
  }

  /// The instruction after `code[at]`, a widening conversion skipped.
  private static Optional<Instruction> next(List<CodeElement> code, int at) {
    var following = instructionsAfter(code, at, 2);
    if (following.isEmpty()) {
      return Optional.empty();
    }
    var first = following.getFirst();
    if (first instanceof ConvertInstruction convert && convert.opcode() == Opcode.I2L) {
      return following.size() > 1 ? Optional.of(following.get(1)) : Optional.empty();
    }
    return Optional.of(first);
  }

  /// `load a; load b; if a ? b goto L; ...` where both ways leave the same value when `a == b`: the
  /// body stores one into the other's slot and ends at `L`, or the two branches load one each and
  /// meet.
  private static boolean sameValueAtTheEdge(List<CodeElement> code, int at) {
    if (!(code.get(at) instanceof BranchInstruction branch) || !comparesTwoInts(branch)) {
      return false;
    }
    var compared = loadsBefore(code, at);
    if (compared.size() != 2) {
      return false;
    }
    return storesOneIntoTheOther(code, at, branch.target(), compared)
        || picksOneOrTheOther(code, at, branch.target(), compared);
  }

  private static boolean comparesTwoInts(BranchInstruction branch) {
    return switch (branch.opcode()) {
      case IF_ICMPLT, IF_ICMPLE, IF_ICMPGT, IF_ICMPGE -> true;
      default -> false;
    };
  }

  /// The slots of the two `int` loads just before `code[at]`, with no label between: nothing else
  /// jumps in. Empty if it isn't that.
  private static List<Integer> loadsBefore(List<CodeElement> code, int at) {
    var slots = new ArrayList<Integer>();
    for (int index = at - 1; index >= 0 && slots.size() < 2; index--) {
      var element = code.get(index);
      if (element instanceof LoadInstruction load && load.opcode().name().startsWith("ILOAD")) {
        slots.addFirst(load.slot());
      } else if (element instanceof Instruction || element instanceof LabelTarget) {
        return List.of();
      }
    }
    return slots;
  }

  /// `if (x > max) max = x;`: `load one; store the other; target:`.
  private static boolean storesOneIntoTheOther(
      List<CodeElement> code, int at, Label target, List<Integer> compared) {
    var next = significantAfter(code, at, 3);
    return next.size() == 3
        && next.get(0) instanceof LoadInstruction load
        && next.get(1) instanceof StoreInstruction store
        && next.get(2) instanceof LabelTarget end
        && end.label().equals(target)
        && compared.contains(load.slot())
        && compared.contains(store.slot())
        && load.slot() != store.slot();
  }

  /// `a < b ? a : b`: `load one; goto END; target: load the other; END:`.
  private static boolean picksOneOrTheOther(
      List<CodeElement> code, int at, Label target, List<Integer> compared) {
    var next = significantAfter(code, at, 5);
    return next.size() == 5
        && next.get(0) instanceof LoadInstruction first
        && next.get(3) instanceof LoadInstruction second
        && armsMeet(next, target)
        && compared.contains(first.slot())
        && compared.contains(second.slot())
        && first.slot() != second.slot();
  }

  /// `goto END; target: …; END:`: the first arm jumps past the second, which starts at `target`.
  private static boolean armsMeet(List<CodeElement> next, Label target) {
    return next.get(1) instanceof BranchInstruction jump
        && jump.opcode() == Opcode.GOTO
        && next.get(2) instanceof LabelTarget other
        && other.label().equals(target)
        && next.get(4) instanceof LabelTarget end
        && end.label().equals(jump.target());
  }

  /// The next `count` instructions and labels after `code[at]`, line numbers and the like left out.
  private static List<CodeElement> significantAfter(List<CodeElement> code, int at, int count) {
    var found = new ArrayList<CodeElement>();
    for (int index = at + 1; index < code.size() && found.size() < count; index++) {
      var element = code.get(index);
      if (element instanceof Instruction || element instanceof LabelTarget) {
        found.add(element);
      }
    }
    return found;
  }

  /// The next `count` instructions after `code[at]`, stopping at a label: past one, other code can
  /// jump in.
  private static List<Instruction> instructionsAfter(List<CodeElement> code, int at, int count) {
    var found = new ArrayList<Instruction>();
    for (var element : significantAfter(code, at, count + 1)) {
      if (element instanceof LabelTarget || found.size() == count) {
        break;
      }
      found.add((Instruction) element);
    }
    return found;
  }
}
