package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Operator;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// Mutants judged by the code right around their instruction, on positive evidence in the bytecode:
///
/// - **arid:** arithmetic whose result only sizes a collection's first allocation (`new
///   ArrayList<>(n * 2)`) or sets how long to wait (`Thread.sleep(seconds * 1000)`), and a removed
///   call that only waits. What a test sees doesn't change, only how fast it runs.
/// - **equivalent:** a boundary where both sides give the same value at the edge: `if (x > max) max
///   = x;` stores the value `max` already holds when they're equal, `a < b ? a : b` gives either,
///   as they're the same, and so do `x < 10 ? 10 : x` and `if (x < 2) x = 2;`, and `x > 3 ? x : 3`
///   with `x` an `Integer`, unboxed each time it's read.
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

  /// The operators that change a value worked out, or given, by the code.
  private static final Set<Operator> CALCULATIONS =
      Set.of(
          BuiltIn.MATH,
          BuiltIn.ARITHMETIC_REPLACEMENT,
          BuiltIn.OTHER_ARITHMETIC,
          BuiltIn.BITWISE,
          BuiltIn.KEEP_OPERAND,
          BuiltIn.REMOVE_NEGATION,
          BuiltIn.CONSTANT_VALUE);

  /// The calls that unbox a whole number: its value, the same each time it's asked for.
  private static final Set<String> UNBOXING =
      Set.of("java/lang/Integer.intValue", "java/lang/Long.longValue");

  private SiteSkips() {}

  /// Why the mutant `operator` makes at `code[at]` shouldn't run, if it shouldn't.
  static Optional<String> reason(Operator operator, List<CodeElement> code, int at) {
    if (CALCULATIONS.contains(operator)) {
      return next(code, at).flatMap(SiteSkips::onlySizesOrWaits);
    }
    if (code.get(at) instanceof InvokeInstruction call) {
      return removedCall(operator, call);
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

  /// A removed call that only waits, or only checks a value isn't null.
  private static Optional<String> removedCall(Operator operator, InvokeInstruction call) {
    if (operator == BuiltIn.REMOVE_VOID_CALL && waits(call)) {
      return Optional.of("arid: only waits");
    }
    if (operator == BuiltIn.REMOVE_DISCARDED_CALL && onlyChecksForNull(call)) {
      return Optional.of("arid: only checks the value isn't null");
    }
    return Optional.empty();
  }

  /// `Objects.requireNonNull(x)`, or `x.getClass()`, its result thrown away: what javac writes to
  /// check a value isn't null (`outer.new Inner()`, `value::method`), and what code writes to check
  /// an argument. Without it the code fails later, or not at all, on a `null` no test passes.
  private static boolean onlyChecksForNull(InvokeInstruction call) {
    var owner = call.owner().asInternalName();
    var name = call.name().stringValue();
    return (owner.equals("java/util/Objects") && name.startsWith("requireNonNull"))
        || (name.equals("getClass") && call.typeSymbol().parameterCount() == 0);
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

  /// `if a ? b goto L; ...` where both ways leave the same value when `a == b`: the body stores one
  /// into the other's slot and ends at `L`, or the two branches give one each and meet. `a` and `b`
  /// are `int` or `long` locals, or one of them a constant: `x < 10 ? 10 : x`, `if (x < 2) x = 2;`
  /// (`x < 0 ? 0 : x` compares with zero, `IFLT`, and a `long` goes through `LCMP` first). Not
  /// `float` or `double`, where `-0.0` and `0.0` compare equal but aren't the same value.
  private static boolean sameValueAtTheEdge(List<CodeElement> code, int at) {
    if (!(code.get(at) instanceof BranchInstruction branch)) {
      return false;
    }
    var compared = comparedBefore(code, at, branch);
    if (compared.size() != 2 || compared.get(0).equals(compared.get(1))) {
      return false;
    }
    return storesOneIntoTheOther(code, at, branch.target(), compared)
        || picksOneOrTheOther(code, at, branch.target(), compared);
  }

  /// What the jump compares, as [#value]s: two ints (`IF_ICMPLT`), an int with zero (`IFLT`), or
  /// two longs (`LCMP; IFLT`). Empty if it isn't one of those, or something else jumps in between.
  private static List<String> comparedBefore(
      List<CodeElement> code, int at, BranchInstruction branch) {
    var opcode = branch.opcode();
    var before = ImpliedOr.instructionsBefore(code, at, 5);
    if (comparesTwoInts(branch)) {
      return lastValues(before, 2);
    }
    if (!isEdge(opcode)) {
      return List.of();
    }
    if (!before.isEmpty() && before.getLast().opcode() == Opcode.LCMP) {
      return lastValues(before.subList(0, before.size() - 1), 2);
    }
    var compared = lastValues(before, 1);
    return compared.isEmpty() || compared.getFirst().startsWith("constant")
        ? List.of()
        : List.of(compared.getFirst(), "constant 0");
  }

  /// The last `count` values `instructions` push, as [#value]s, read back from the end; empty if
  /// something else comes first.
  private static List<String> lastValues(List<Instruction> instructions, int count) {
    var found = new ArrayList<String>();
    int end = instructions.size();
    while (found.size() < count && end > 0) {
      var pushed = pushedEndingAt(instructions, end);
      if (pushed == null) {
        return List.of();
      }
      found.addFirst(pushed.value());
      end -= pushed.length();
    }
    return found.size() == count ? found : List.of();
  }

  /// The value pushed by the instructions just before `end`: one [#value], or a boxed local read
  /// and unboxed.
  private static @Nullable Pushed pushedEndingAt(List<? extends CodeElement> elements, int end) {
    var unboxed = end >= 2 ? pushedFrom(elements.subList(end - 2, end), 0) : null;
    return unboxed != null && unboxed.length() == 2
        ? unboxed
        : pushedFrom(elements.subList(end - 1, end), 0);
  }

  /// The value pushed by the elements from `from`, as [#pushedEndingAt]; null if they don't push
  /// one.
  private static @Nullable Pushed pushedFrom(List<? extends CodeElement> elements, int from) {
    if (from + 1 < elements.size()) {
      var unboxed = unboxed(elements.get(from), elements.get(from + 1));
      if (unboxed != null) {
        return new Pushed(unboxed, 2);
      }
    }
    var value = from < elements.size() ? value(elements.get(from)) : null;
    return value == null ? null : new Pushed(value, 1);
  }

  /// A value and how many instructions push it.
  private record Pushed(String value, int length) {}

  /// `unboxed slot 2` for `ALOAD 2; INVOKEVIRTUAL Integer.intValue`, or `Long.longValue`: the boxed
  /// local's value, the same each time it's read; null for anything else.
  private static @Nullable String unboxed(CodeElement load, CodeElement call) {
    return load instanceof LoadInstruction boxed
            && boxed.opcode().name().startsWith("ALOAD")
            && call instanceof InvokeInstruction unboxing
            && unboxing.opcode() == Opcode.INVOKEVIRTUAL
            && UNBOXING.contains(
                unboxing.owner().asInternalName() + "." + unboxing.name().stringValue())
        ? "unboxed slot " + boxed.slot()
        : null;
  }

  private static boolean isEdge(Opcode opcode) {
    return opcode == Opcode.IFLT
        || opcode == Opcode.IFLE
        || opcode == Opcode.IFGT
        || opcode == Opcode.IFGE;
  }

  /// What an instruction puts on the stack, said so two can be compared: `slot 3` for a load of an
  /// `int` or `long` local, `constant 10` for a constant; null for anything else.
  private static @Nullable String value(CodeElement element) {
    if (element instanceof LoadInstruction load
        && (load.opcode().name().startsWith("ILOAD") || load.opcode().name().startsWith("LLOAD"))) {
      return "slot " + load.slot();
    }
    if (element instanceof ConstantInstruction constant
        && (constant.constantValue() instanceof Integer
            || constant.constantValue() instanceof Long)) {
      return "constant " + ((Number) constant.constantValue()).longValue();
    }
    return null;
  }

  private static boolean comparesTwoInts(BranchInstruction branch) {
    return switch (branch.opcode()) {
      case IF_ICMPLT, IF_ICMPLE, IF_ICMPGT, IF_ICMPGE -> true;
      default -> false;
    };
  }

  /// `if (x > max) max = x;`, `if (x < 2) x = 2;`: `push one; store the other; target:`.
  private static boolean storesOneIntoTheOther(
      List<CodeElement> code, int at, Label target, List<String> compared) {
    var next = significantAfter(code, at, 3);
    return next.size() == 3
        && next.get(1) instanceof StoreInstruction store
        && next.get(2) instanceof LabelTarget end
        && end.label().equals(target)
        && bothOf(compared, value(next.get(0)), "slot " + store.slot());
  }

  /// `a < b ? a : b`, `x < 10 ? 10 : x`: `push one; goto END; target: push the other; END:`.
  private static boolean picksOneOrTheOther(
      List<CodeElement> code, int at, Label target, List<String> compared) {
    var next = significantAfter(code, at, 7);
    var first = pushedFrom(next, 0);
    if (first == null) {
      return false;
    }
    int jump = first.length();
    var second = pushedFrom(next, jump + 2);
    return second != null
        && next.size() > jump + 2 + second.length()
        && armsMeet(
            next.get(jump), next.get(jump + 1), next.get(jump + 2 + second.length()), target)
        && bothOf(compared, first.value(), second.value());
  }

  /// Whether `one` and `other` are the two compared values, either way round.
  private static boolean bothOf(
      List<String> compared, @Nullable String one, @Nullable String other) {
    return one != null
        && other != null
        && !one.equals(other)
        && compared.contains(one)
        && compared.contains(other);
  }

  /// `goto END; target: …; END:`: the first arm jumps past the second, which starts at `target`.
  private static boolean armsMeet(
      CodeElement goTo, CodeElement second, CodeElement after, Label target) {
    return goTo instanceof BranchInstruction jump
        && jump.opcode() == Opcode.GOTO
        && second instanceof LabelTarget other
        && other.label().equals(target)
        && after instanceof LabelTarget end
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
  static List<Instruction> instructionsAfter(List<CodeElement> code, int at, int count) {
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
