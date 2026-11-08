package io.mutastic.engine;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// What the Compose compiler writes into a `@Composable` function around the person's code: the
/// `Composer` it passes in, the groups it starts and ends, the `$changed` and `$default` masks it
/// works out to skip a call whose arguments haven't changed, its trace and source information, and
/// the cache that `remember` reads and writes. A mutant of any of it asks for a test of code nobody
/// wrote; what the person wrote in the same function keeps its mutants.
///
/// Told from the person's code by what's in the bytecode. A composable function takes a `Composer`,
/// then one or more `int` masks, last. In one:
///
/// - every call to the Compose runtime's own bookkeeping (`Composer`, `ComposerKt`, the restart
///   scope, a composable lambda's wrapper), and what's put on the stack for it: a group's key, the
///   source information's text;
/// - every calculation, comparison and store that works on a mask, or on what a runtime call
///   answered (`changed(x)`, `getSkipping()`, `isTraceInProgress()`, whether a cached value is
///   `Composer.Empty`), followed through the code as values on the stack and in locals;
/// - the masks given to another composable function it calls.
final class Compose {
  private static final ClassDesc COMPOSER = ClassDesc.of("androidx.compose.runtime.Composer");

  /// The Compose runtime's bookkeeping, by owner: what a person never calls themselves.
  private static final Set<String> RUNTIME =
      Set.of(
          "androidx/compose/runtime/Composer",
          "androidx/compose/runtime/Composer$Companion",
          "androidx/compose/runtime/ComposerKt",
          "androidx/compose/runtime/RecomposeScopeImplKt",
          "androidx/compose/runtime/ScopeUpdateScope",
          "androidx/compose/runtime/internal/ComposableLambdaKt");

  /// The fields a restart lambda keeps its function's masks in.
  private static final Set<String> MASK_FIELDS =
      Set.of("$changed", "$default", "$changed1", "$default1");

  static final String REASON =
      "generated: the Compose compiler's bookkeeping, not this function's logic";

  /// One value on the stack: whether it comes from a mask or the runtime, and the instructions that
  /// worked it out, to mark with whatever uses it.
  private record Value(boolean compose, List<Integer> from) {
    static final Value UNKNOWN = new Value(false, List.of());
  }

  private final List<Instruction> instructions = new ArrayList<>();
  private final List<Integer> places = new ArrayList<>();
  private final Map<Integer, Label> labelsBefore = new HashMap<>();
  private final Set<Integer> maskSlots = new HashSet<>();
  private final Set<Integer> marked = new HashSet<>();
  private final Map<Label, List<Value>> atLabel = new HashMap<>();

  private Compose(List<CodeElement> code) {
    for (int at = 0; at < code.size(); at++) {
      switch (code.get(at)) {
        case LabelTarget target -> labelsBefore.put(instructions.size(), target.label());
        case Instruction instruction -> {
          instructions.add(instruction);
          places.add(at);
        }
        default -> {}
      }
    }
  }

  /// The places in the code of what the Compose compiler wrote: none in a method that isn't a
  /// composable function, or a composable lambda's body.
  static Set<Integer> machinery(MethodModel method, List<CodeElement> code) {
    var masks = maskSlots(method);
    if (masks.isEmpty()) {
      return Set.of();
    }
    var found = new Compose(code);
    found.maskSlots.addAll(masks);
    found.read();
    var places = new HashSet<Integer>();
    found.marked.forEach(index -> places.add(found.places.get(index)));
    return places;
  }

  /// The slots of the `int` masks after the `Composer`, when the method takes one; none otherwise.
  static Set<Integer> maskSlots(MethodModel method) {
    var type = method.methodTypeSymbol();
    var parameters = type.parameterList();
    int composer = parameters.indexOf(COMPOSER);
    if (composer < 0 || composer == parameters.size() - 1) {
      return Set.of();
    }
    boolean masksLast =
        parameters.subList(composer + 1, parameters.size()).stream()
            .allMatch(parameter -> parameter.descriptorString().equals("I"));
    if (!masksLast) {
      return Set.of();
    }
    int slot = method.flags().has(java.lang.reflect.AccessFlag.STATIC) ? 0 : 1;
    var masks = new HashSet<Integer>();
    for (int at = 0; at < parameters.size(); at++) {
      if (at > composer) {
        masks.add(slot);
      }
      slot += java.lang.classfile.TypeKind.from(parameters.get(at)).slotSize();
    }
    return masks;
  }

  /// Whether a call takes a `Composer` and its masks: another composable function.
  private static boolean isComposableCall(MethodTypeDesc type) {
    var parameters = type.parameterList();
    int composer = parameters.indexOf(COMPOSER);
    return composer >= 0
        && composer < parameters.size() - 1
        && parameters.subList(composer + 1, parameters.size()).stream()
            .allMatch(parameter -> parameter.descriptorString().equals("I"));
  }

  private void read() {
    var stack = new ArrayList<Value>();
    boolean reachable = true;
    for (int at = 0; at < instructions.size(); at++) {
      var label = labelsBefore.get(at);
      var saved = label == null ? null : atLabel.get(label);
      if (saved != null) {
        stack = new ArrayList<>(reachable ? merged(stack, saved) : saved);
        reachable = true;
      } else if (!reachable) {
        stack = new ArrayList<>(); // a handler, or code only a switch reaches
        reachable = true;
      }
      reachable = step(at, stack);
    }
  }

  private static List<Value> merged(List<Value> one, List<Value> other) {
    var merged = new ArrayList<Value>();
    for (int at = 0; at < Math.max(one.size(), other.size()); at++) {
      var mine = at < one.size() ? one.get(at) : Value.UNKNOWN;
      var theirs = at < other.size() ? other.get(at) : Value.UNKNOWN;
      var from = new ArrayList<>(mine.from());
      from.addAll(theirs.from());
      merged.add(new Value(mine.compose() || theirs.compose(), from.stream().distinct().toList()));
    }
    return merged;
  }

  /// Runs one instruction over the stack; whether the next instruction is reached from it.
  private boolean step(int at, List<Value> stack) {
    var instruction = instructions.get(at);
    switch (instruction) {
      case InvokeInstruction call -> call(at, call, stack);
      case BranchInstruction branch -> {
        return branch(at, branch, stack);
      }
      case TableSwitchInstruction _, LookupSwitchInstruction _ -> {
        markIfCompose(at, pop(stack, 1));
        return false;
      }
      case ReturnInstruction _, ThrowInstruction _ -> {
        return false;
      }
      case StoreInstruction store -> store(at, store, stack);
      case LoadInstruction load ->
          stack.add(
              new Value(
                  maskSlots.contains(load.slot()) && isPrimitive(load.typeKind()), List.of(at)));
      case StackInstruction shuffle -> shuffle(shuffle.opcode(), stack);
      default -> other(at, instruction, stack);
    }
    return true;
  }

  /// A jump: what it compares, and the stack where it lands. Whether it can fall through.
  private boolean branch(int at, BranchInstruction branch, List<Value> stack) {
    var used = pop(stack, branchPops(branch.opcode()));
    markIfCompose(at, used);
    atLabel.merge(branch.target(), List.copyOf(stack), Compose::merged);
    return branch.opcode() != Opcode.GOTO && branch.opcode() != Opcode.GOTO_W;
  }

  private static int branchPops(Opcode opcode) {
    return switch (opcode) {
      case GOTO, GOTO_W -> 0;
      case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, IFNULL, IFNONNULL -> 1;
      default -> 2;
    };
  }

  private void call(int at, InvokeInstruction call, List<Value> stack) {
    var effect = StackProducers.effect(call).orElseThrow();
    var arguments = pop(stack, effect.pops());
    boolean runtime = RUNTIME.contains(call.owner().asInternalName());
    if (runtime) {
      marked.add(at);
      arguments.forEach(this::markFrom);
    } else if (isComposableCall(call.typeSymbol())) {
      var parameters = call.typeSymbol().parameterList();
      int composer = parameters.indexOf(COMPOSER);
      int masks = parameters.size() - composer - 1;
      arguments.subList(arguments.size() - masks, arguments.size()).forEach(this::markFrom);
      markDefaulted(
          arguments.subList(arguments.size() - masks - composer - 1, arguments.size()),
          composer,
          masks);
    }
    if (effect.pushes() > 0) {
      stack.add(new Value(runtime, List.of()));
    }
  }

  /// The stand-ins a composable call passes for the arguments it leaves to their defaults: a zero
  /// or a `null` where its `$default` mask has the argument's bit set. The callee never reads them.
  /// A call has one `$changed` mask for every ten parameters, then, when the callee has defaults,
  /// one `$default` mask for every 31.
  ///
  /// @param arguments the call's arguments, without a receiver: its parameters, the `Composer`, the
  ///   masks
  private void markDefaulted(List<Value> arguments, int parameters, int masks) {
    int changed = parameters == 0 ? 1 : (parameters + 9) / 10;
    int defaults = masks - changed;
    for (int mask = 0; mask < defaults; mask++) {
      var bits = constant(arguments.get(parameters + 1 + changed + mask));
      for (int bit = 0; bit < Integer.SIZE - 1 && bits.isPresent(); bit++) {
        int parameter = mask * (Integer.SIZE - 1) + bit;
        if ((bits.get() & (1 << bit)) != 0 && parameter < parameters) {
          var argument = arguments.get(parameter);
          if (constant(argument).filter(value -> value == 0).isPresent() || isNull(argument)) {
            markFrom(argument);
          }
        }
      }
    }
  }

  /// The whole number a value is, when one constant instruction put it there.
  private java.util.Optional<Long> constant(Value value) {
    return value.from().size() == 1
            && instructions.get(value.from().getFirst())
                instanceof java.lang.classfile.instruction.ConstantInstruction constant
            && constant.constantValue() instanceof Number number
        ? java.util.Optional.of(number.longValue())
        : java.util.Optional.empty();
  }

  private boolean isNull(Value value) {
    return value.from().size() == 1
        && instructions.get(value.from().getFirst()).opcode() == Opcode.ACONST_NULL;
  }

  private void store(int at, StoreInstruction store, List<Value> stack) {
    var stored = pop(stack, 1);
    if (isPrimitive(store.typeKind()) && stored.getFirst().compose()) {
      marked.add(at);
      markFrom(stored.getFirst());
      maskSlots.add(store.slot());
    }
  }

  private static boolean isPrimitive(java.lang.classfile.TypeKind kind) {
    return kind != java.lang.classfile.TypeKind.REFERENCE;
  }

  private void other(int at, Instruction instruction, List<Value> stack) {
    var effect = StackProducers.effect(instruction);
    if (effect.isEmpty()) {
      stack.clear(); // what this doesn't follow: nothing below it is known
      return;
    }
    var used = pop(stack, effect.get().pops());
    boolean compose = used.stream().anyMatch(Value::compose) || isMaskField(instruction);
    if (compose) {
      marked.add(at);
      used.forEach(this::markFrom);
    }
    if (effect.get().pushes() > 0) {
      var from = new ArrayList<Integer>(List.of(at));
      used.forEach(value -> from.addAll(value.from()));
      stack.add(new Value(compose, compose ? List.of() : from));
    }
  }

  private static boolean isMaskField(Instruction instruction) {
    return instruction instanceof FieldInstruction field
        && field.opcode() == Opcode.GETFIELD
        && MASK_FIELDS.contains(field.name().stringValue());
  }

  private void markIfCompose(int at, List<Value> used) {
    if (used.stream().anyMatch(Value::compose)) {
      marked.add(at);
      used.forEach(this::markFrom);
    }
  }

  private void markFrom(Value value) {
    marked.addAll(value.from());
  }

  private static void shuffle(Opcode opcode, List<Value> stack) {
    switch (opcode) {
      case POP -> pop(stack, 1);
      case POP2 -> pop(stack, 1);
      case DUP -> stack.add(stack.isEmpty() ? Value.UNKNOWN : stack.getLast());
      default -> stack.clear();
    }
  }

  /// The top `count` values, deepest first; unknown ones where the stack ran out.
  private static List<Value> pop(List<Value> stack, int count) {
    var popped = new ArrayList<Value>();
    for (int at = 0; at < count; at++) {
      popped.addFirst(stack.isEmpty() ? Value.UNKNOWN : stack.removeLast());
    }
    return popped;
  }
}
