package io.mutastic.engine;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ConstantDescs;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.IntStream;

/// `limit` on a stream the method can be seen to start from `iterate` or `generate`, where it's all
/// that ends the stream. Without it the tests hang, and a hang counted as a catch shows nothing.
///
/// Only what's visible counts: anything this doesn't follow, or anything else that ends the stream
/// (the three-argument `iterate`, another `limit`, `takeWhile`, a `findFirst`), and the mutant
/// stays.
final class EndlessStreams {
  private static final Set<String> STREAMS =
      Set.of(
          "java/util/stream/Stream",
          "java/util/stream/IntStream",
          "java/util/stream/LongStream",
          "java/util/stream/DoubleStream");

  private static final Set<String> ENDS_A_STREAM =
      Set.of(
          "limit",
          "takeWhile",
          "findFirst",
          "findAny",
          "anyMatch",
          "allMatch",
          "noneMatch",
          "iterator",
          "spliterator");

  private EndlessStreams() {}

  /// How many values an instruction takes from the stack and leaves: longs and doubles count once.
  private record Effect(int taken, int left) {
    /// None for an instruction this doesn't follow.
    static Optional<Effect> of(Instruction instruction) {
      return switch (instruction) {
        case LoadInstruction _, ConstantInstruction _ -> Optional.of(new Effect(0, 1));
        case FieldInstruction field when field.opcode() == Opcode.GETSTATIC ->
            Optional.of(new Effect(0, 1));
        case FieldInstruction field when field.opcode() == Opcode.GETFIELD ->
            Optional.of(new Effect(1, 1));
        case ConvertInstruction _, TypeCheckInstruction _ -> Optional.of(new Effect(1, 1));
        case InvokeDynamicInstruction call ->
            Optional.of(new Effect(call.typeSymbol().parameterCount(), 1));
        case InvokeInstruction call ->
            Optional.of(new Effect(takenBy(call), returns(call) ? 1 : 0));
        default -> Optional.empty();
      };
    }
  }

  /// Why `limit` at `code[at]` can't be removed, if it's all that ends its stream.
  static Optional<String> whyNotRemoved(InvokeInstruction call, List<CodeElement> code, int at) {
    return call.name().equalsString("limit")
            && isStream(call)
            && startsEndless(code, at)
            && !endedLater(code, at)
        ? Optional.of("unviable: without `.limit(…)` the stream never ends")
        : Optional.empty();
  }

  /// Back along the calls on the stream, stage by stage, to the call that made it.
  private static boolean startsEndless(List<CodeElement> code, int limit) {
    var source = receiver(code, limit);
    while (source.isPresent()) {
      if (!(code.get(source.getAsInt()) instanceof InvokeInstruction call)
          || !isStream(call)
          || ENDS_A_STREAM.contains(call.name().stringValue())) {
        return false;
      }
      if (call.opcode() == Opcode.INVOKESTATIC) {
        return isEndlessSource(call);
      }
      source = receiver(code, source.getAsInt());
    }
    return false;
  }

  /// `iterate(seed, next)` or `generate(supplier)`; `iterate(seed, hasNext, next)` can end.
  private static boolean isEndlessSource(InvokeInstruction call) {
    return call.name().equalsString("generate")
        || (call.name().equalsString("iterate") && call.typeSymbol().parameterCount() == 2);
  }

  /// What leaves the value the call at `at` is on, through the variable it was kept in if it has
  /// only the one value.
  private static OptionalInt receiver(List<CodeElement> code, int at) {
    var call = (InvokeInstruction) code.get(at);
    var found = leftBelow(code, at, call.typeSymbol().parameterCount());
    if (found.isPresent() && code.get(found.getAsInt()) instanceof LoadInstruction load) {
      var stores =
          IntStream.range(0, code.size())
              .filter(
                  index ->
                      code.get(index) instanceof StoreInstruction store
                          && store.slot() == load.slot())
              .toArray();
      return stores.length == 1 ? leftBelow(code, stores[0], 0) : OptionalInt.empty();
    }
    return found;
  }

  /// Back along straight code from `code[at]`, to what left the value with `above` values on top of
  /// it; none if a label comes first, where other code can jump in, or anything this doesn't
  /// follow.
  private static OptionalInt leftBelow(List<CodeElement> code, int at, int above) {
    int values = above;
    for (int index = at - 1; index >= 0; index--) {
      if (code.get(index) instanceof LabelTarget) {
        return OptionalInt.empty();
      }
      if (code.get(index) instanceof Instruction instruction) {
        var effect = Effect.of(instruction);
        if (effect.isEmpty()) {
          return OptionalInt.empty();
        }
        if (values == 0 && effect.get().left() == 1 && !isCast(instruction)) {
          return OptionalInt.of(index);
        }
        values += effect.get().taken() - effect.get().left();
      }
    }
    return OptionalInt.empty();
  }

  private static boolean isCast(Instruction instruction) {
    return instruction instanceof TypeCheckInstruction;
  }

  /// On along the calls on the stream from `limit`, for one that would end it anyway.
  private static boolean endedLater(List<CodeElement> code, int limit) {
    var next = consumerOf(code, limit);
    while (next.isPresent()) {
      if (!(code.get(next.getAsInt()) instanceof InvokeInstruction call)
          || !isStream(call)
          || !returns(call)) {
        return false;
      }
      if (ENDS_A_STREAM.contains(call.name().stringValue())) {
        return true;
      }
      next = consumerOf(code, next.getAsInt());
    }
    return false;
  }

  /// On along straight code from `code[at]`, to what takes the value it leaves.
  private static OptionalInt consumerOf(List<CodeElement> code, int at) {
    int above = 0;
    for (int index = at + 1; index < code.size(); index++) {
      if (code.get(index) instanceof LabelTarget) {
        return OptionalInt.empty();
      }
      if (code.get(index) instanceof Instruction instruction && !isCast(instruction)) {
        var effect = Effect.of(instruction);
        if (effect.isEmpty()) {
          return OptionalInt.empty();
        }
        if (effect.get().taken() > above) {
          return OptionalInt.of(index);
        }
        above += effect.get().left() - effect.get().taken();
      }
    }
    return OptionalInt.empty();
  }

  private static boolean isStream(InvokeInstruction call) {
    return STREAMS.contains(call.owner().asInternalName());
  }

  private static int takenBy(InvokeInstruction call) {
    return call.typeSymbol().parameterCount() + (call.opcode() == Opcode.INVOKESTATIC ? 0 : 1);
  }

  private static boolean returns(InvokeInstruction call) {
    return !call.typeSymbol().returnType().equals(ConstantDescs.CD_void);
  }
}
