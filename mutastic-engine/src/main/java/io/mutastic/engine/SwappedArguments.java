package io.mutastic.engine;

import io.mutastic.api.Mutator;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.constant.ConstantDescs;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// [io.mutastic.api.BuiltIn#SWAP_ARGUMENTS]: a call's last two arguments, where they have the same
/// type, swapped on the stack just before it. That's every pair the stack can reach: an earlier
/// pair has the later arguments on top of it, and is left alone.
///
/// `Object` doesn't count as a type here, as it's what a type variable compiles to: `map.put(key,
/// value)` swapped would put a value of the wrong type in the map, and the `ClassCastException`
/// that follows would look like a catch.
final class SwappedArguments {
  static final String SWAP = "swap last two arguments";

  private static final Set<String> ALGEBRA = Set.of("max", "min", "sum");

  /// Methods that give the same result either way round.
  private static final Map<String, Set<String>> COMMUTATIVE =
      Map.of(
          "java/lang/Math", Set.of("max", "min", "addExact", "multiplyExact", "hypot"),
          "java/lang/StrictMath", Set.of("max", "min", "addExact", "multiplyExact", "hypot"),
          "java/lang/Integer", ALGEBRA,
          "java/lang/Long", ALGEBRA,
          "java/lang/Float", ALGEBRA,
          "java/lang/Double", ALGEBRA);

  private SwappedArguments() {}

  /// The change at the call at `code[at]`, if its last two arguments have the same type.
  static Optional<Mutator.Change> at(java.util.List<CodeElement> code, int at) {
    if (!(code.get(at) instanceof InvokeInstruction call) || !lastTwoMatch(call)) {
      return Optional.empty();
    }
    return Optional.of(
        new Mutator.Change(SWAP, commutative(call).or(() -> sameValueTwice(code, at))));
  }

  private static boolean lastTwoMatch(InvokeInstruction call) {
    var parameters = call.typeSymbol().parameterList();
    int count = parameters.size();
    return count >= 2
        && parameters.get(count - 1).equals(parameters.get(count - 2))
        && !parameters.get(count - 1).equals(ConstantDescs.CD_Object);
  }

  private static Optional<String> commutative(InvokeInstruction call) {
    var owner = call.owner().asInternalName();
    var name = call.name().stringValue();
    return COMMUTATIVE.getOrDefault(owner, Set.of()).contains(name)
        ? Optional.of(
            "equivalent: `%s.%s` gives the same either way round"
                .formatted(owner.substring(owner.lastIndexOf('/') + 1), name))
        : Optional.empty();
  }

  /// `f(x, x)` or `f(1, 1)`: the two instructions before the call load the same thing, with nothing
  /// able to jump in between.
  private static Optional<String> sameValueTwice(java.util.List<CodeElement> code, int at) {
    var before = ImpliedOr.instructionsBefore(code, at, 2);
    return before.size() == 2 && same(before.get(0), before.get(1))
        ? Optional.of("equivalent: both arguments are the same value")
        : Optional.empty();
  }

  private static boolean same(Instruction one, Instruction other) {
    if (one instanceof LoadInstruction first && other instanceof LoadInstruction second) {
      return first.slot() == second.slot() && first.typeKind() == second.typeKind();
    }
    return one instanceof ConstantInstruction first
        && other instanceof ConstantInstruction second
        && first.constantValue().equals(second.constantValue());
  }

  /// The two on top of the stack swapped: `dup2_x2; pop2` where each takes two slots.
  static void write(CodeBuilder builder, InvokeInstruction call) {
    var last = call.typeSymbol().parameterList().getLast();
    if (TypeKind.from(last).slotSize() == 2) {
      builder.dup2_x2().pop2();
    } else {
      builder.swap();
    }
    builder.with(call);
  }
}
