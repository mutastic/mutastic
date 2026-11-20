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
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// Mutants judged by the code right around their instruction, on positive evidence in the bytecode:
///
/// - **arid:** a number whose only use is a collection's first allocation (`new ArrayList<>(n *
///   2)`, `HashMap.newHashMap(n)`), when a hash table grows (`new HashMap<>(n, 0.75f)`) or how long
///   a sleep waits (`Thread.sleep(seconds * 1000)`, `Thread.sleep(Duration.ofSeconds(n))`), and a
///   removed sleep. What a test sees doesn't change, only how fast it runs. A number made negative
///   where that throws (`new ArrayList<>(-1)`, `Thread.sleep(-1)`) is counted: any test that runs
///   the line sees it.
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

  /// Hash tables whose constructor takes a capacity, then a load factor: `(IF)V`, and
  /// `LinkedHashMap`'s `(IFZ)V`.
  private static final Set<String> LOADED =
      Set.of(
          "java/util/HashMap",
          "java/util/HashSet",
          "java/util/LinkedHashMap",
          "java/util/LinkedHashSet",
          "java/util/WeakHashMap",
          "java/util/concurrent/ConcurrentHashMap");

  /// Factories whose one argument is only how many entries to make room for: the JDK's since 19,
  /// and Guava's.
  private static final Set<String> SIZED_FACTORIES =
      Set.of(
          "java/util/HashMap.newHashMap",
          "java/util/HashSet.newHashSet",
          "java/util/LinkedHashMap.newLinkedHashMap",
          "java/util/LinkedHashSet.newLinkedHashSet",
          "java/util/WeakHashMap.newWeakHashMap",
          "com/google/common/collect/Lists.newArrayListWithCapacity",
          "com/google/common/collect/Lists.newArrayListWithExpectedSize",
          "com/google/common/collect/Maps.newHashMapWithExpectedSize",
          "com/google/common/collect/Maps.newLinkedHashMapWithExpectedSize",
          "com/google/common/collect/Sets.newHashSetWithExpectedSize",
          "com/google/common/collect/Sets.newLinkedHashSetWithExpectedSize",
          "com/google/common/collect/ImmutableList.builderWithExpectedSize",
          "com/google/common/collect/ImmutableSet.builderWithExpectedSize",
          "com/google/common/collect/ImmutableMap.builderWithExpectedSize");

  /// What turns a number into a time and nothing else, so a sleep's time can be followed through
  /// it: `Duration.ofSeconds(n)`, `TimeUnit.SECONDS.toMillis(n)`.
  private static final Set<String> TO_A_TIME =
      Set.of(
          "java/time/Duration.ofNanos",
          "java/time/Duration.ofMillis",
          "java/time/Duration.ofSeconds",
          "java/time/Duration.ofMinutes",
          "java/time/Duration.ofHours",
          "java/time/Duration.ofDays",
          "java/util/concurrent/TimeUnit.toNanos",
          "java/util/concurrent/TimeUnit.toMicros",
          "java/util/concurrent/TimeUnit.toMillis",
          "java/util/concurrent/TimeUnit.toSeconds",
          "java/util/concurrent/TimeUnit.toMinutes",
          "java/util/concurrent/TimeUnit.toHours",
          "java/util/concurrent/TimeUnit.toDays");

  private static final String SIZES = "arid: only sizes a collection's first allocation";
  private static final String GROWS = "arid: only sets when a hash table grows";
  private static final String WAITS = "arid: only sets how long to wait";

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

  /// Why the mutant `operator` makes at `code[at]`, its change `label`, shouldn't run, if it
  /// shouldn't.
  static Optional<String> reason(Operator operator, String label, List<CodeElement> code, int at) {
    if (CALCULATIONS.contains(operator)) {
      return takenBy(code, at)
          .flatMap(taken -> onlySizesOrWaits(taken, changedTo(operator, label)));
    }
    if (code.get(at) instanceof InvokeInstruction call) {
      return removedCall(operator, call);
    }
    if (operator == BuiltIn.BOUNDARY && sameValueAtTheEdge(code, at)) {
      return Optional.of("equivalent: both sides give the same value at the edge");
    }
    return Optional.empty();
  }

  /// The number a constant becomes, `-1` for `constant 16 to -1`; none for any other change, whose
  /// value the code works out as it runs.
  private static Optional<Double> changedTo(Operator operator, String label) {
    if (operator != BuiltIn.CONSTANT_VALUE) {
      return Optional.empty();
    }
    try {
      return Optional.of(Double.parseDouble(label.substring(label.lastIndexOf(' ') + 1)));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /// The call a number goes to, and where in it: the call's receiver is `0`, as [Taken] says.
  private record Taken(InvokeInstruction call, int position) {
    String owner() {
      return call.owner().asInternalName();
    }

    String name() {
      return call.name().stringValue();
    }

    String descriptor() {
      return call.typeSymbol().descriptorString();
    }
  }

  /// The number goes only to a capacity or a wait, and a changed constant stays a value it takes
  /// without throwing.
  private static Optional<String> onlySizesOrWaits(Taken taken, Optional<Double> to) {
    boolean negative = to.filter(value -> value < 0).isPresent();
    if (sizes(taken)) {
      return negative ? Optional.empty() : Optional.of(SIZES);
    }
    if (LOADED.contains(taken.owner())
        && taken.name().equals("<init>")
        && taken.position() == 2
        && (taken.descriptor().equals("(IF)V") || taken.descriptor().equals("(IFZ)V"))) {
      // A load factor above zero only says when the table grows; zero or below throws, and so might
      // whatever the code works out.
      return to.filter(value -> value > 0).map(value -> GROWS);
    }
    if (sleepsFor(taken.call())) {
      boolean throwsIfNegative =
          taken.owner().equals("java/lang/Thread") && taken.descriptor().startsWith("(J");
      return negative && throwsIfNegative ? Optional.empty() : Optional.of(WAITS);
    }
    return Optional.empty();
  }

  private static boolean sizes(Taken taken) {
    var owner = taken.owner();
    var name = taken.name();
    if (name.equals("<init>")) {
      var descriptor = taken.descriptor();
      return taken.position() == 1
          && ((SIZED.contains(owner) && descriptor.equals("(I)V"))
              || (LOADED.contains(owner)
                  && (descriptor.equals("(IF)V") || descriptor.equals("(IFZ)V"))));
    }
    return (name.equals("ensureCapacity") && SIZED.contains(owner))
        || (SIZED_FACTORIES.contains(owner + "." + name)
            && taken.call().opcode() == Opcode.INVOKESTATIC
            && taken.descriptor().startsWith("(I)"));
  }

  /// Follows the number `code[at]` leaves through what only works with it, arithmetic, a widening
  /// or a time's unit, to the call that takes it, in straight-line code: past a jump, or a place
  /// one lands, the number may go somewhere else.
  private static Optional<Taken> takenBy(List<CodeElement> code, int at) {
    var landings = landings(code);
    int depth = 1;
    for (int index = at + 1; index < code.size(); index++) {
      var element = code.get(index);
      if (element instanceof LabelTarget target && landings.contains(target.label())) {
        return Optional.empty();
      }
      if (!(element instanceof Instruction instruction)) {
        continue;
      }
      if (instruction instanceof BranchInstruction
          || instruction instanceof ReturnInstruction
          || instruction instanceof ThrowInstruction
          || instruction instanceof TableSwitchInstruction
          || instruction instanceof LookupSwitchInstruction) {
        return Optional.empty();
      }
      var effect = TextConstants.Follow.effect(instruction);
      if (effect.takes() < depth) {
        depth += effect.leaves() - effect.takes();
        continue;
      }
      if (instruction instanceof InvokeInstruction call) {
        int takes = TextConstants.Follow.takes(call);
        var taken = new Taken(call, takes - depth);
        if (!TO_A_TIME.contains(taken.owner() + "." + taken.name())) {
          return Optional.of(taken);
        }
      } else if (!worksWith(instruction)) {
        return Optional.empty();
      }
      // What it works out holds the number, and is on top.
      depth = 1;
    }
    return Optional.empty();
  }

  /// Arithmetic, or a conversion from one kind of number to another: what's left is the number,
  /// worked with. Not a comparison, whose result says which is bigger.
  private static boolean worksWith(Instruction instruction) {
    return switch (instruction) {
      case ConvertInstruction _ -> true;
      case OperatorInstruction operator ->
          switch (operator.opcode()) {
            case LCMP, FCMPL, FCMPG, DCMPL, DCMPG, ARRAYLENGTH -> false;
            default -> true;
          };
      default -> false;
    };
  }

  /// The labels code jumps to, or a handler starts at.
  private static Set<Label> landings(List<CodeElement> code) {
    var found = new HashSet<Label>();
    for (var element : code) {
      switch (element) {
        case BranchInstruction branch -> found.add(branch.target());
        case TableSwitchInstruction table -> {
          found.add(table.defaultTarget());
          table.cases().forEach(each -> found.add(each.target()));
        }
        case LookupSwitchInstruction lookup -> {
          found.add(lookup.defaultTarget());
          lookup.cases().forEach(each -> found.add(each.target()));
        }
        case ExceptionCatch handler -> found.add(handler.handler());
        default -> {}
      }
    }
    return found;
  }

  /// A removed sleep, or a call that only checks a value isn't null. A `join` with a time limit
  /// isn't one: without it the code reads what the thread does before it's done, as without one
  /// with none.
  private static Optional<String> removedCall(Operator operator, InvokeInstruction call) {
    if (operator == BuiltIn.REMOVE_VOID_CALL && sleeps(call)) {
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

  /// `Thread.sleep` or `TimeUnit.sleep`.
  private static boolean sleeps(InvokeInstruction call) {
    var owner = call.owner().asInternalName();
    var name = call.name().stringValue();
    return (owner.equals("java/lang/Thread") && name.equals("sleep"))
        || (owner.equals("java/util/concurrent/TimeUnit") && name.equals("sleep"));
  }

  /// A sleep that takes how long: `Thread.sleep(ms)`, `Thread.sleep(Duration)` or `TimeUnit.sleep`.
  /// Not a `join` or a `wait` with a time limit: past it, the code goes on whether or not what it
  /// waited for happened, and a test can see which.
  private static boolean sleepsFor(InvokeInstruction call) {
    return sleeps(call) && call.typeSymbol().parameterCount() > 0;
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
