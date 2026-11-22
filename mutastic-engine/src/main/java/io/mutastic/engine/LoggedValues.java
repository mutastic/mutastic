package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Operator;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.NopInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ConstantDescs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// `arid: only feeds a log line`: a value worked out only for a log call or a print, which only a
/// test that reads the log tells apart. `log.info("took {}", System.currentTimeMillis() - start)`,
/// and the same through a local, `long took = now - start; log.info("took {}", took)`.
///
/// The value a mutant changes is followed forward in the bytecode, wherever it goes, through what
/// only works out more from it: arithmetic, conversions, boxing, `String.valueOf`, `"a" + x` (a
/// builder before Java 9, an `invokedynamic` since), an array built for a varargs call, a ternary,
/// the methods of `java.time` and of `Math`. Every path has to end in a call of one of the loggers
/// `mutastic:output` knows, [Mutations#OUTPUT_OWNERS]. Stored in a local, every read of it on any
/// path, a loop's or a `catch`'s included, has to end in one too. Anything else that takes it keeps
/// the mutant: a comparison, a `return`, a field, a call of any other method, a metric's.
///
/// The changes it judges change only a value: arithmetic, a number, a `boolean` or a string in the
/// code, `Math.min` swapped for `Math.max`, a step or an assignment of a local. A call left out
/// ([BuiltIn#OMIT_CALL]) isn't one: the call's work goes with it, and it may do more than give a
/// value.
final class LoggedValues {
  static final String REASON = "arid: only feeds a log line";

  /// The operators whose change is the value one instruction leaves on the stack.
  private static final Set<Operator> VALUES =
      Set.of(
          BuiltIn.MATH,
          BuiltIn.ARITHMETIC_REPLACEMENT,
          BuiltIn.OTHER_ARITHMETIC,
          BuiltIn.BITWISE,
          BuiltIn.KEEP_OPERAND,
          BuiltIn.REMOVE_NEGATION,
          BuiltIn.CONSTANT_VALUE,
          BuiltIn.BOOLEAN_CONSTANT,
          BuiltIn.STRING_CONSTANT);

  /// The operators whose change is the value a local is left with.
  private static final Set<Operator> LOCALS =
      Set.of(BuiltIn.FLIP_INCREMENT, BuiltIn.REMOVE_ASSIGNMENT);

  /// How far back a walk starts, in the code's elements.
  private static final int REACH = 200;

  /// How many locals one value may pass through, one after another or side by side, before the walk
  /// gives up and keeps the mutant.
  private static final int BUDGET = 64;

  /// Classes each of whose methods only gives a value from its arguments, changing none of them.
  private static final Set<String> VALUE_CLASSES =
      Set.of(
          "java/lang/Integer",
          "java/lang/Long",
          "java/lang/Short",
          "java/lang/Byte",
          "java/lang/Character",
          "java/lang/Boolean",
          "java/lang/Float",
          "java/lang/Double",
          "java/lang/Math",
          "java/lang/StrictMath",
          "java/math/BigInteger",
          "java/math/BigDecimal");

  /// `String`'s methods that only give a value: not `getChars` or `getBytes` into an array.
  private static final Set<String> STRING_METHODS =
      Set.of(
          "valueOf",
          "format",
          "formatted",
          "join",
          "concat",
          "toString",
          "length",
          "substring",
          "trim",
          "strip",
          "toUpperCase",
          "toLowerCase",
          "repeat");

  /// `TimeUnit`'s conversions: not its waits.
  private static final Set<String> TIME_UNIT_METHODS =
      Set.of(
          "convert",
          "toNanos",
          "toMicros",
          "toMillis",
          "toSeconds",
          "toMinutes",
          "toHours",
          "toDays",
          "toChronoUnit");

  private final List<CodeElement> code;
  private final Stores stores;
  private final Set<Integer> followed = new HashSet<>();

  /// How many ways in each label has besides falling through: the jumps to it, and a handler's
  /// start as many as can be.
  private final Map<Label, Integer> jumpsTo = new HashMap<>();

  private LoggedValues(List<CodeElement> code, Stores stores) {
    this.code = code;
    this.stores = stores;
    for (var element : code) {
      switch (element) {
        case BranchInstruction branch -> jumpsTo.merge(branch.target(), 1, Integer::sum);
        case TableSwitchInstruction choice -> {
          jumpsTo.put(choice.defaultTarget(), Integer.MAX_VALUE);
          choice.cases().forEach(each -> jumpsTo.put(each.target(), Integer.MAX_VALUE));
        }
        case LookupSwitchInstruction choice -> {
          jumpsTo.put(choice.defaultTarget(), Integer.MAX_VALUE);
          choice.cases().forEach(each -> jumpsTo.put(each.target(), Integer.MAX_VALUE));
        }
        case ExceptionCatch handler -> jumpsTo.put(handler.handler(), Integer.MAX_VALUE);
        default -> {}
      }
    }
  }

  /// Where a walk to `at` starts: back along the code to where another way in joins it, or where
  /// the way before it ends, so that what the value is put in, a varargs array or a builder made
  /// before it, is seen made. Not further than [#REACH] back.
  private int origin(int at) {
    int origin = at;
    while (origin > 0 && at - origin < REACH) {
      var before = code.get(origin - 1);
      if (before instanceof LabelTarget target && jumpsTo.containsKey(target.label())) {
        return origin - 1;
      }
      if (before instanceof Instruction instruction && !continues(instruction)) {
        return origin;
      }
      origin--;
    }
    return origin;
  }

  /// The kinds of instruction the walk follows, and the code goes on from to the next.
  private static final List<Class<? extends Instruction>> FOLLOWED =
      List.of(
          InvokeInstruction.class,
          InvokeDynamicInstruction.class,
          StoreInstruction.class,
          ArrayStoreInstruction.class,
          LoadInstruction.class,
          ConstantInstruction.class,
          IncrementInstruction.class,
          NopInstruction.class,
          NewObjectInstruction.class,
          NewReferenceArrayInstruction.class,
          NewPrimitiveArrayInstruction.class,
          ConvertInstruction.class,
          TypeCheckInstruction.class,
          OperatorInstruction.class,
          FieldInstruction.class,
          ArrayLoadInstruction.class);

  /// An instruction the code goes on from to the next, of a kind the walk follows.
  private static boolean continues(Instruction instruction) {
    return switch (instruction) {
      case BranchInstruction branch -> tested(branch.opcode()) > 0;
      case StackInstruction moved -> movesValues(moved.opcode());
      default -> FOLLOWED.stream().anyMatch(kind -> kind.isInstance(instruction));
    };
  }

  /// What a jump tests, in values: none for a `goto`.
  private static int tested(Opcode opcode) {
    return switch (opcode) {
      case GOTO, GOTO_W -> 0;
      case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE ->
          2;
      default -> 1;
    };
  }

  /// Only what moves single values: the rest work in slots, and a `long` takes two of those.
  private static boolean movesValues(Opcode opcode) {
    return switch (opcode) {
      case POP, DUP, DUP_X1, SWAP -> true;
      default -> false;
    };
  }

  /// Why the mutant `operator` makes at `code[at]` is arid, if all it changes only feeds a log
  /// line.
  static java.util.Optional<String> reason(
      Operator operator, List<CodeElement> code, int at, Stores stores) {
    var values = new LoggedValues(code, stores);
    boolean logged =
        switch (code.get(at)) {
          case IncrementInstruction step when LOCALS.contains(operator) ->
              values.localOnlyLogged(step.slot(), at);
          case StoreInstruction store when LOCALS.contains(operator) ->
              values.localOnlyLogged(store.slot(), at);
          case OperatorInstruction _, ConstantInstruction _ when VALUES.contains(operator) ->
              values.onlyLogged(at);
          // `Math.min` for `Math.max`: one value for another, and nothing else.
          case InvokeInstruction call
              when operator.id().equals("library/swap-math")
                  && call.owner().asInternalName().equals("java/lang/Math") ->
              values.onlyLogged(at);
          default -> false;
        };
    return logged ? java.util.Optional.of(REASON) : java.util.Optional.empty();
  }

  /// Whether every read of what the slot holds after `at` only feeds a log line. A store the walk
  /// is already following is taken as one: a loop that only passes the value round adds no use.
  private boolean localOnlyLogged(int slot, int at) {
    if (!followed.add(at)) {
      return true;
    }
    if (followed.size() > BUDGET) {
      return false;
    }
    var reads = stores.readsOf(slot, at);
    return reads.isPresent() && reads.get().stream().allMatch(this::onlyLogged);
  }

  /// Whether the value `code[at]` leaves on the stack only feeds a log line.
  private boolean onlyLogged(int at) {
    var walk = new Walk();
    if (!walk.from(origin(at), at)) {
      return false;
    }
    for (var store : walk.stores) {
      if (!localOnlyLogged(((StoreInstruction) code.get(store)).slot(), store)) {
        return false;
      }
    }
    return true;
  }

  /// A value on the stack: whether it's worked out from the one followed, and the object it is if
  /// the walk saw it made, a builder or an array, so that what's put in it can be followed too.
  private record Value(boolean followed, int object) {
    static final Value OTHER = new Value(false, 0);
  }

  /// The stack on one path: the values from the walk's start up, and how many of what was below the
  /// start the path has taken.
  private record State(List<Value> stack, int below) {
    int height() {
      return stack.size() - below;
    }
  }

  /// A walk forward from the value, with the stack from it up as values. What's below it is only
  /// known when an instruction takes it, as some other value. A jump ahead carries the stack to its
  /// label; code no path reaches with the value is passed over.
  private final class Walk {
    private final Map<Label, State> ahead = new HashMap<>();
    private final Set<Label> passed = new HashSet<>();

    /// Objects made in the walk that something else got hold of: whatever goes in them after that
    /// may go anywhere.
    private final Set<Integer> escaped = new HashSet<>();

    /// The stores of the value into locals, whose reads are followed in turn.
    final List<Integer> stores = new ArrayList<>();

    private final Map<Label, Integer> jumpsSeen = new HashMap<>();
    private List<Value> stack = new ArrayList<>();
    private int below;
    private boolean reached = true;
    private int made;

    /// Whether every path from the value `code[at]` leaves takes it only into log calls, or stores
    /// it, followed from `origin`, before it, on.
    boolean from(int origin, int at) {
      for (int index = origin; index < code.size(); index++) {
        if (index > at && done()) {
          return true;
        }
        if (!pass(code.get(index), index) || (index == at && !mark())) {
          return false;
        }
      }
      return false;
    }

    /// Whether the walk goes on past `element`.
    private boolean pass(CodeElement element, int index) {
      return switch (element) {
        case LabelTarget target -> arrive(target.label());
        case Instruction instruction when reached -> step(instruction, index);
        default -> true;
      };
    }

    /// The value just left on the stack is the one followed.
    private boolean mark() {
      if (!reached || stack.isEmpty()) {
        return false;
      }
      stack.addLast(new Value(true, stack.removeLast().object()));
      return true;
    }

    /// Nothing worked out from the value is left, on this path or one still to arrive.
    private boolean done() {
      return (!reached || noneFollowed(stack))
          && ahead.values().stream().allMatch(state -> noneFollowed(state.stack()));
    }

    private boolean arrive(Label label) {
      passed.add(label);
      var carried = ahead.remove(label);
      if (carried != null) {
        var merged = reached ? merge(state(), carried) : carried;
        if (merged == null) {
          return false;
        }
        stack = new ArrayList<>(merged.stack());
        below = merged.below();
        reached = true;
      }
      // A way in the walk didn't take may bring other objects: which object a value is isn't known.
      if (jumpsTo.getOrDefault(label, 0) > jumpsSeen.getOrDefault(label, 0)) {
        stack.replaceAll(each -> new Value(each.followed(), 0));
      }
      return true;
    }

    private boolean step(Instruction instruction, int index) {
      return switch (instruction) {
        case BranchInstruction branch -> jump(branch);
        case InvokeInstruction call -> call(call);
        case InvokeDynamicInstruction call -> dynamic(call);
        case StoreInstruction _ -> store(index);
        case ArrayStoreInstruction _ -> arrayStore();
        case StackInstruction moved -> stack(moved.opcode());
        case LoadInstruction _, ConstantInstruction _ -> push(Value.OTHER);
        case IncrementInstruction _, NopInstruction _ -> true;
        case NewObjectInstruction _ -> push(new Value(false, ++made));
        case NewReferenceArrayInstruction _, NewPrimitiveArrayInstruction _ ->
            take(1).stream().noneMatch(Value::followed) && push(new Value(false, ++made));
        case ConvertInstruction _ -> push(new Value(take(1).getFirst().followed(), 0));
        case TypeCheckInstruction check -> typeCheck(check);
        case OperatorInstruction operator -> operator(operator.opcode());
        case FieldInstruction field -> field(field.opcode());
        case ArrayLoadInstruction _ ->
            take(2).stream().noneMatch(Value::followed) && push(Value.OTHER);
        case ReturnInstruction _, ThrowInstruction _ -> end();
        default -> false;
      };
    }

    /// A path that ends here, with nothing of the value on it: else it goes back to a caller.
    private boolean end() {
      if (!noneFollowed(stack)) {
        return false;
      }
      stack = new ArrayList<>();
      below = 0;
      reached = false;
      return true;
    }

    /// A jump takes what it tests, which mustn't be the value: then the value decides what runs.
    private boolean jump(BranchInstruction branch) {
      int tested = tested(branch.opcode());
      if (take(tested).stream().anyMatch(Value::followed)) {
        return false;
      }
      jumpsSeen.merge(branch.target(), 1, Integer::sum);
      // Back, as a loop goes, only with nothing of the value on the stack.
      boolean carried = passed.contains(branch.target()) ? noneFollowed(stack) : carry(branch);
      reached = reached && tested > 0;
      return carried;
    }

    /// The stack goes with the jump to its label, to meet what else arrives there.
    private boolean carry(BranchInstruction branch) {
      var before = ahead.get(branch.target());
      var merged = before == null ? state() : merge(before, state());
      if (merged == null) {
        return false;
      }
      ahead.put(branch.target(), merged);
      return true;
    }

    private boolean call(InvokeInstruction call) {
      int count =
          call.typeSymbol().parameterCount() + (call.opcode() == Opcode.INVOKESTATIC ? 0 : 1);
      var taken = take(count);
      boolean returns = !call.typeSymbol().returnType().equals(ConstantDescs.CD_void);
      var owner = call.owner().asInternalName();
      if (owner.equals("java/lang/StringBuilder") || owner.equals("java/lang/StringBuffer")) {
        return builder(call.name().stringValue(), taken, returns);
      }
      if (Mutations.OUTPUT_OWNERS.contains(owner) || noneFollowed(taken)) {
        taken.forEach(this::letGo);
        return !returns || push(Value.OTHER);
      }
      if (!onlyGivesAValue(owner, call.name().stringValue())) {
        return false;
      }
      taken.forEach(this::letGo);
      return !returns || push(new Value(true, 0));
    }

    /// A builder made in the walk takes the value in, and is followed itself from then on.
    private boolean builder(String name, List<Value> taken, boolean returns) {
      var builder = taken.getFirst();
      boolean followed = !noneFollowed(taken);
      return switch (name) {
        case "<init>", "append", "insert" -> {
          if (followed && (builder.object() == 0 || !hold(builder.object()))) {
            yield false;
          }
          taken.subList(1, taken.size()).forEach(this::letGo);
          yield !returns || push(new Value(followed, builder.object()));
        }
        case "toString", "length" -> push(new Value(builder.followed(), 0));
        default -> !followed && letGoAll(taken) && (!returns || push(Value.OTHER));
      };
    }

    private boolean dynamic(InvokeDynamicInstruction call) {
      var taken = take(call.typeSymbol().parameterCount());
      boolean returns = !call.typeSymbol().returnType().equals(ConstantDescs.CD_void);
      if (noneFollowed(taken)) {
        taken.forEach(this::letGo);
        return !returns || push(Value.OTHER);
      }
      // `"a" + x` since Java 9: the text made of them, and nothing else.
      return call.bootstrapMethod()
              .owner()
              .descriptorString()
              .equals("Ljava/lang/invoke/StringConcatFactory;")
          && push(new Value(true, 0));
    }

    private boolean store(int index) {
      var value = take(1).getFirst();
      letGo(value);
      if (value.followed()) {
        if (value.object() != 0) {
          return false;
        }
        stores.add(index);
      }
      return true;
    }

    /// What goes in an array made in the walk is followed with the array, as for a varargs call.
    private boolean arrayStore() {
      var taken = take(3);
      var array = taken.get(0);
      if (taken.get(1).followed()) {
        return false;
      }
      return !taken.get(2).followed() || (array.object() != 0 && hold(array.object()));
    }

    private boolean typeCheck(TypeCheckInstruction check) {
      var value = take(1).getFirst();
      return check.opcode() == Opcode.CHECKCAST
          ? push(value)
          : push(new Value(value.followed(), 0));
    }

    private boolean operator(Opcode opcode) {
      var taken =
          take(
              switch (opcode) {
                case INEG, LNEG, FNEG, DNEG, ARRAYLENGTH -> 1;
                default -> 2;
              });
      return push(new Value(!noneFollowed(taken), 0));
    }

    private boolean field(Opcode opcode) {
      return switch (opcode) {
        case GETSTATIC -> push(Value.OTHER);
        case GETFIELD -> !take(1).getFirst().followed() && push(Value.OTHER);
        case PUTSTATIC -> letGoAll(take(1));
        default -> letGoAll(take(2));
      };
    }

    /// Only what [#movesValues]. A value worked out and thrown away isn't one a log line is made
    /// of: javac doesn't write that, so code that does may do more with it than the walk sees.
    private boolean stack(Opcode opcode) {
      return switch (opcode) {
        case POP -> letGoAll(take(1));
        case DUP -> putBack(take(1), 0, 0);
        case DUP_X1 -> putBack(take(2), 1, 0, 1);
        case SWAP -> putBack(take(2), 1, 0);
        default -> false;
      };
    }

    /// Puts back what was taken, in the order given, the first deepest.
    private boolean putBack(List<Value> taken, int... order) {
      for (int each : order) {
        push(taken.get(each));
      }
      return true;
    }

    /// Something other than the walk gets hold of what it took: fine for a value worked out from
    /// another, but not for one worked out from the one followed.
    private boolean letGoAll(List<Value> taken) {
      if (!noneFollowed(taken)) {
        return false;
      }
      taken.forEach(this::letGo);
      return true;
    }

    private void letGo(Value value) {
      if (value.object() != 0 && !value.followed()) {
        escaped.add(value.object());
      }
    }

    /// The object now holds what's followed, as does every copy of it on the stack.
    private boolean hold(int object) {
      if (escaped.contains(object)
          || ahead.values().stream()
              .anyMatch(
                  state -> state.stack().stream().anyMatch(each -> each.object() == object))) {
        return false;
      }
      stack.replaceAll(each -> each.object() == object ? new Value(true, object) : each);
      return true;
    }

    /// The top `count` values, the deepest first, taken off: what's below the walk's start is some
    /// other value.
    private List<Value> take(int count) {
      var taken = new ArrayList<Value>();
      for (int each = 0; each < count; each++) {
        if (stack.isEmpty()) {
          below++;
          taken.addFirst(Value.OTHER);
        } else {
          taken.addFirst(stack.removeLast());
        }
      }
      return taken;
    }

    private boolean push(Value value) {
      stack.add(value);
      return true;
    }

    private State state() {
      return new State(List.copyOf(stack), below);
    }
  }

  /// A method that gives a value from what it's given, and changes none of it.
  private static boolean onlyGivesAValue(String owner, String name) {
    if (VALUE_CLASSES.contains(owner)) {
      return true;
    }
    return switch (owner) {
      case "java/lang/String" -> STRING_METHODS.contains(name);
      case "java/util/Objects" -> name.equals("toString");
      case "java/util/Arrays" -> name.equals("toString") || name.equals("deepToString");
      case "java/util/concurrent/TimeUnit" -> TIME_UNIT_METHODS.contains(name);
      // Values that never change; `formatTo` writes into what it's given.
      default -> owner.startsWith("java/time/") && !name.equals("formatTo");
    };
  }

  private static boolean noneFollowed(List<Value> values) {
    return values.stream().noneMatch(Value::followed);
  }

  /// Two stacks that meet at a label: the same height, and a value followed if either way it is.
  /// What one path never saw of the stack below the start is some other value.
  private static @Nullable State merge(State one, State other) {
    if (one.height() != other.height()) {
      return null;
    }
    int size = Math.max(one.stack().size(), other.stack().size());
    var merged = new ArrayList<Value>();
    for (int at = 0; at < size; at++) {
      var mine = valueAt(one.stack(), size, at);
      var theirs = valueAt(other.stack(), size, at);
      merged.add(
          new Value(
              mine.followed() || theirs.followed(),
              mine.object() == theirs.object() ? mine.object() : 0));
    }
    return new State(List.copyOf(merged), size - one.height());
  }

  /// The value `at` from the bottom of a stack `size` high, of which `stack` is the top.
  private static Value valueAt(List<Value> stack, int size, int at) {
    int index = at - (size - stack.size());
    return index < 0 ? Value.OTHER : stack.get(index);
  }
}
