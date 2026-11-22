package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Operator;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
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
import java.util.OptionalLong;
import java.util.Set;

/// Mutants that change only how a thread looks or what it costs, not whether, when or how often
/// work runs:
///
/// - **arid:** a thread's or a pool's name (`setName`, `new Thread(task, name)`, `Thread.Builder`'s
///   `name`, Guava's `setNameFormat`), which nothing in the JVM reads, its priority (a hint, which
///   Linux ignores by default and a virtual thread always), how long a pool's idle threads live,
///   and a thread's stack size while it stays above zero.
/// - **equivalent:** a pool's largest size, when its queue has no limit: the pool only grows past
///   its core size when the queue is full, and such a queue never is.
///
/// A class that reads names (`getName()`, other than into a log line), or is a `ThreadFactory`,
/// whose job names are, keeps its name mutants; one that reads priorities keeps those; one that
/// changes or reads a pool's sizes keeps them. A priority outside 1 to 10, a stack size or keep-
/// alive of zero or less, and a start index below zero throw, or may, and stay counted.
final class ThreadSettings {
  static final String NAMES = "arid: only names a thread";
  static final String PRIORITY = "arid: only sets a priority hint";
  static final String KEEP_ALIVE = "arid: only sets how long idle threads live";
  static final String STACK = "arid: only sizes a thread's stack";
  static final String LARGEST_POOL =
      "equivalent: differs only when the pool grows past its core size. That can't happen because"
          + " its queue has no limit, so it never fills.";

  private static final String THREAD = "java/lang/Thread";
  private static final String POOL = "java/util/concurrent/ThreadPoolExecutor";
  private static final String GUAVA = "com/google/common/util/concurrent/ThreadFactoryBuilder";

  /// `Thread.ofPlatform()` and `Thread.ofVirtual()`'s builders, as javac calls them.
  private static final Set<String> BUILDERS =
      Set.of(
          "java/lang/Thread$Builder",
          "java/lang/Thread$Builder$OfPlatform",
          "java/lang/Thread$Builder$OfVirtual");

  /// What only names a thread, as [#called] says it.
  private static final Set<String> NAMING =
      Set.of("thread.setName", "builder.name", "guava.setNameFormat");

  /// What only sets a thread's priority, as [#called] says it.
  private static final Set<String> PRIORITIES =
      Set.of("thread.setPriority", "builder.priority", "guava.setPriority");

  /// Queues that take a bound, made without one.
  private static final Set<String> UNBOUNDED =
      Set.of(
          "java/util/concurrent/LinkedBlockingQueue",
          "java/util/concurrent/LinkedBlockingDeque",
          "java/util/concurrent/LinkedTransferQueue",
          "java/util/concurrent/PriorityBlockingQueue");

  /// What a pool's sizes are read or changed by, after it's made.
  private static final Set<String> RESIZING =
      Set.of("setCorePoolSize", "setMaximumPoolSize", "getMaximumPoolSize");

  /// What a class shows of how it treats its threads: whether it reads their names, or their
  /// priorities, or changes or reads a pool's sizes after making it.
  record Facts(boolean readsNames, boolean readsPriorities, boolean resizesPools) {
    static final Facts NONE = new Facts(false, false, false);

    static Facts of(ClassModel model) {
      boolean factory =
          model.interfaces().stream()
              .anyMatch(each -> each.asInternalName().equals("java/util/concurrent/ThreadFactory"));
      boolean names = factory;
      boolean priorities = false;
      boolean sizes = false;
      for (MethodModel method : model.methods()) {
        var code = method.code().map(java.lang.classfile.CodeModel::elementList).orElse(List.of());
        for (int at = 0; at < code.size(); at++) {
          if (code.get(at) instanceof InvokeInstruction call) {
            names |= readsAName(call, code, at, method);
            priorities |= isCall(call, THREAD, "getPriority");
            sizes |= RESIZING.contains(call.name().stringValue());
          }
        }
      }
      return new Facts(names, priorities, sizes);
    }

    /// `thread.getName()`, unless what it gives only goes into a log line or a print.
    private static boolean readsAName(
        InvokeInstruction call, List<CodeElement> code, int at, MethodModel method) {
      if (!isCall(call, THREAD, "getName")) {
        return false;
      }
      var outputs = new OutputCalls(code, method);
      return !new ThreadSettings(code, NONE)
          .goesThroughTextInto(at, taken -> outputs.writes(taken.index()));
    }
  }

  /// A call, or javac's `invokedynamic`, that takes the value pushed at a place in the code: where
  /// the value is among those it takes (`0` an instance call's receiver), where the call is, and
  /// what's pushed after the value, as far as it's known, bottom first: `new
  /// java/util/concurrent/LinkedBlockingQueue()V` for a queue made with no bound.
  private record Taken(Instruction call, int position, int index, List<String> after) {
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

    String called() {
      return ThreadSettings.called(owner(), name());
    }

    boolean is(String owner, String name, int position) {
      return owner().equals(owner) && name().equals(name) && this.position == position;
    }
  }

  private final List<CodeElement> code;
  private final Facts facts;

  ThreadSettings(List<CodeElement> code, Facts facts) {
    this.code = code;
    this.facts = facts;
  }

  /// Why the mutant `operator` makes at `code[at]`, labelled `label`, shouldn't run, if it's one of
  /// these.
  Optional<String> reason(Operator operator, String label, int at) {
    if (operator == BuiltIn.REMOVE_VOID_CALL || operator == BuiltIn.REMOVE_DISCARDED_CALL) {
      return code.get(at) instanceof InvokeInstruction call ? removed(call) : Optional.empty();
    }
    if (operator == BuiltIn.STRING_CONSTANT) {
      return names(goesThroughTextInto(at, ThreadSettings::namesAThread));
    }
    if (operator == BuiltIn.NULL_PARAMETER) {
      return names(onlyNamesThroughText(slotIn(label)));
    }
    if (operator == BuiltIn.CONSTANT_VALUE) {
      var to = changedTo(label);
      return to.isPresent() ? number(at, to.getAsLong()) : Optional.empty();
    }
    return Optional.empty();
  }

  private Optional<String> names(boolean names) {
    return names && !facts.readsNames() ? Optional.of(NAMES) : Optional.empty();
  }

  /// A call taken away that only names a thread, sets its priority or its stack size.
  private Optional<String> removed(InvokeInstruction call) {
    var called = called(call.owner().asInternalName(), call.name().stringValue());
    if (NAMING.contains(called)) {
      return names(true);
    }
    if (PRIORITIES.contains(called)) {
      return facts.readsPriorities() ? Optional.empty() : Optional.of(PRIORITY);
    }
    return called.equals("builder.stackSize") ? Optional.of(STACK) : Optional.empty();
  }

  /// `thread.setName`, `builder.name`, `guava.setNameFormat`: a call by what it's on, if it's a
  /// thread, one of its builders or Guava's; empty for any other.
  private static String called(String owner, String name) {
    if (owner.equals(THREAD)) {
      return "thread." + name;
    }
    if (BUILDERS.contains(owner)) {
      return "builder." + name;
    }
    return owner.equals(GUAVA) ? "guava." + name : "";
  }

  /// A number changed to `to`, where the number goes straight into a thread's or a pool's setting.
  private Optional<String> number(int at, long to) {
    return taken(at).flatMap(call -> setting(call, to).or(() -> largestPoolSize(at, call, to)));
  }

  /// A priority stays a hint from 1 to 10, and throws outside; a name's start index throws below
  /// zero; a stack size or keep-alive of zero or less throws, or means something else.
  private Optional<String> setting(Taken call, long to) {
    if (isPriority(call)) {
      return priority(to);
    }
    if (isNameStart(call)) {
      return to >= 0 ? names(true) : Optional.empty();
    }
    if (to <= 0) {
      return Optional.empty();
    }
    if (isStackSize(call)) {
      return Optional.of(STACK);
    }
    return call.is(POOL, "<init>", 3) || call.is(POOL, "setKeepAliveTime", 1)
        ? Optional.of(KEEP_ALIVE)
        : Optional.empty();
  }

  private Optional<String> priority(long to) {
    return to >= Thread.MIN_PRIORITY && to <= Thread.MAX_PRIORITY && !facts.readsPriorities()
        ? Optional.of(PRIORITY)
        : Optional.empty();
  }

  private static boolean isPriority(Taken call) {
    return PRIORITIES.contains(call.called()) && call.position() == 1;
  }

  /// The number a `Thread.Builder`'s names count up from.
  private static boolean isNameStart(Taken call) {
    return call.called().equals("builder.name") && call.position() == 2;
  }

  /// `new Thread(group, task, name, stackSize)`, or a builder's `stackSize(n)`.
  private static boolean isStackSize(Taken call) {
    return (call.owner().equals(THREAD)
            && call.name().equals("<init>")
            && call.parameter().filter("J"::equals).isPresent())
        || (BUILDERS.contains(call.owner()) && call.name().equals("stackSize"));
  }

  /// `new ThreadPoolExecutor(core, largest, …, new LinkedBlockingQueue<>(), …)`, `largest` changed
  /// to no less than `core`, or `1`: below either, the constructor throws.
  private Optional<String> largestPoolSize(int at, Taken call, long to) {
    if (!call.is(POOL, "<init>", 2) || call.after().size() < 3 || facts.resizesPools()) {
      return Optional.empty();
    }
    var queue = call.after().get(2);
    boolean unbounded =
        queue.endsWith("()V") && UNBOUNDED.contains(queue.substring(4, queue.length() - 3));
    var before = ImpliedOr.instructionsBefore(code, at, 1);
    boolean aboveCore =
        !before.isEmpty()
            && before.getFirst() instanceof ConstantInstruction core
            && core.constantValue() instanceof Integer size
            && to >= Math.max(size, 1);
    return unbounded && aboveCore ? Optional.of(LARGEST_POOL) : Optional.empty();
  }

  /// `constant 30 to 29` gives `29`; nothing for a number that isn't whole.
  private static OptionalLong changedTo(String label) {
    try {
      return OptionalLong.of(Long.parseLong(label.substring(label.lastIndexOf(' ') + 1)));
    } catch (NumberFormatException e) {
      return OptionalLong.empty();
    }
  }

  /// `null parameter 2 prefix` gives `2`.
  private static int slotIn(String label) {
    return Integer.parseInt(label.split(" ", 4)[2]);
  }

  /// Whether every read of the parameter in `slot` is made into text first, where `null` is
  /// `"null"`, and the text only names a thread: `new Thread(task, prefix + "-" + n)`. A `null`
  /// name itself throws.
  private boolean onlyNamesThroughText(int slot) {
    int reads = 0;
    for (int at = 0; at < code.size(); at++) {
      if (code.get(at) instanceof LoadInstruction load
          && load.slot() == slot
          && load.opcode().name().startsWith("ALOAD")) {
        var first = taken(at);
        if (first.isEmpty()
            || !makesText(first.get())
            || !goesThroughTextInto(at, ThreadSettings::namesAThread)) {
          return false;
        }
        reads++;
      }
    }
    return reads > 0;
  }

  /// Following the value at `code[at]` through what only makes text of it, whether it ends in a
  /// call `into` takes.
  private boolean goesThroughTextInto(int at, java.util.function.Predicate<Taken> into) {
    var next = taken(at);
    while (next.isPresent()) {
      var call = next.get();
      if (into.test(call)) {
        return true;
      }
      if (!makesText(call)) {
        return false;
      }
      next = taken(call.index());
    }
    return false;
  }

  private static boolean namesAThread(Taken call) {
    var called = call.called();
    return call.parameter().filter("Ljava/lang/String;"::equals).isPresent()
        && (called.equals("thread.<init>") || NAMING.contains(called));
  }

  /// What only makes more text of a value: javac's `"a" + x`, either way it writes it, `format` and
  /// `valueOf`. The next call is on what this one gives, a new builder included.
  private static boolean makesText(Taken call) {
    var name = call.name();
    return switch (call.owner()) {
      case "" -> name.equals("makeConcatWithConstants") || name.equals("makeConcat");
      case "java/lang/String" ->
          name.equals("format") || name.equals("formatted") || name.equals("valueOf");
      case "java/lang/StringBuilder", "java/lang/StringBuffer" ->
          name.equals("<init>") || name.equals("append") || name.equals("toString");
      default -> false;
    };
  }

  /// Follows the value at `code[at]` along straight code to the call that takes it; none if a label
  /// comes first, or an instruction that does anything else with it.
  private Optional<Taken> taken(int at) {
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
          called(instruction, takes, after);
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
  private static void called(Instruction instruction, int takes, List<String> after) {
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
  /// anything with ours but widen it, or this doesn't follow it.
  private static boolean followed(Instruction instruction, List<String> after) {
    boolean ours = after.isEmpty();
    switch (instruction) {
      case LoadInstruction _, ConstantInstruction _ -> after.add("?");
      case FieldInstruction field when field.opcode() == Opcode.GETSTATIC -> after.add("?");
      case NewObjectInstruction made -> after.add("new " + made.className().asInternalName());
      case StackInstruction dup when dup.opcode() == Opcode.DUP && !ours ->
          after.add(after.getLast());
      case Instruction same when ours -> {
        return same.opcode() == Opcode.I2L || same.opcode() == Opcode.CHECKCAST;
      }
      case FieldInstruction field when field.opcode() == Opcode.GETFIELD && !ours ->
          after.set(after.size() - 1, "?");
      case ConvertInstruction _, TypeCheckInstruction _ when !ours ->
          after.set(after.size() - 1, "?");
      case NewReferenceArrayInstruction _ when !ours -> after.set(after.size() - 1, "?");
      case ArrayStoreInstruction _ when after.size() >= 3 -> {
        after.subList(after.size() - 3, after.size()).clear();
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  private static boolean isCall(InvokeInstruction call, String owner, String name) {
    return call.owner().asInternalName().equals(owner) && call.name().equalsString(name);
  }
}
