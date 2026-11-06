package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// The operators that change a calculation beyond [BuiltIn#MATH]'s swaps: what each makes of an
/// instruction, and how it's written.
final class Arithmetic {
  static final String REMOVE_NEGATION = "remove negation";
  static final String KEEP_FIRST = "keep first";
  static final String KEEP_SECOND = "keep second";

  private static final List<String> OPERATIONS = List.of("ADD", "SUB", "MUL", "DIV", "REM");

  /// The changes [BuiltIn#MATH] and [BuiltIn#ARITHMETIC_REPLACEMENT] make, which stay theirs: their
  /// ids are fixed. They only change `int` and `long`.
  private static final Set<String> ALREADY_CHANGED =
      Set.of("ADD SUB", "ADD MUL", "SUB ADD", "MUL DIV", "DIV MUL");

  private Arithmetic() {}

  /// Whether the step has another way to go: not `0`, and not `-32768`, as `iinc` has no `32768`.
  static boolean flips(IncrementInstruction step) {
    return step.constant() != 0 && step.constant() != Short.MIN_VALUE;
  }

  /// The change as it appears in ids: the variable, and the step it takes now, `i by -1`.
  static String flipped(String name, IncrementInstruction step) {
    return "%s by %d".formatted(name, -step.constant());
  }

  /// A negation's change, for each of its four types.
  static List<String> negations(Opcode opcode) {
    return switch (opcode) {
      case INEG, LNEG, FNEG, DNEG -> List.of(REMOVE_NEGATION);
      default -> List.of();
    };
  }

  private static boolean isOperation(Opcode opcode) {
    return OPERATIONS.contains(opcode.name().substring(1))
        && "ILFD".indexOf(opcode.name().charAt(0)) >= 0;
  }

  /// The opcodes `opcode` can become that nothing else makes it, by name.
  static List<String> others(Opcode opcode) {
    if (!isOperation(opcode)) {
      return List.of();
    }
    var type = opcode.name().substring(0, 1);
    var operation = opcode.name().substring(1);
    boolean whole = type.equals("I") || type.equals("L");
    var others = new ArrayList<String>();
    for (String other : OPERATIONS) {
      if (!other.equals(operation)
          && !(whole && ALREADY_CHANGED.contains(operation + " " + other))) {
        others.add(type + other);
      }
    }
    return others;
  }

  /// Which of a calculation's values it can be left with: not a constant beside a variable, as
  /// nothing plausibly forgets the variable. Read from the two instructions before it: the last is
  /// the second value, and the one before that is the first when the second is a plain load.
  static List<String> operandsKept(List<CodeElement> code, int at) {
    if (!isOperation(((Instruction) code.get(at)).opcode())) {
      return List.of();
    }
    var before = ImpliedOr.instructionsBefore(code, at, 2);
    boolean secondConstant = !before.isEmpty() && before.getLast() instanceof ConstantInstruction;
    boolean firstConstant =
        before.size() == 2
            && before.getFirst() instanceof ConstantInstruction
            && before.getLast() instanceof LoadInstruction;
    var kept = new ArrayList<String>();
    if (!firstConstant) {
      kept.add(KEEP_FIRST);
    }
    if (!secondConstant) {
      kept.add(KEEP_SECOND);
    }
    return kept;
  }

  /// An arithmetic instruction as the mutant has it.
  static void write(CodeBuilder builder, OperatorInstruction arithmetic, Mutant mutant) {
    var operator = (BuiltIn) mutant.operator();
    switch (operator) {
      case REMOVE_NEGATION -> {} // the value just stays as it is
      case OTHER_ARITHMETIC ->
          builder.with(OperatorInstruction.of(Opcode.valueOf(mutant.replacement())));
      case KEEP_OPERAND -> keep(builder, arithmetic, mutant.replacement());
      default ->
          builder.with(OperatorInstruction.of(Operators.replace(operator, arithmetic.opcode())));
    }
  }

  /// One of the two values on the stack left there, in place of the calculation's result.
  private static void keep(CodeBuilder builder, OperatorInstruction arithmetic, String kept) {
    boolean wide = arithmetic.typeKind().slotSize() == 2;
    if (kept.equals(KEEP_FIRST)) {
      Mutations.pop(builder, arithmetic.typeKind());
    } else if (wide) {
      builder.dup2_x2().pop2().pop2();
    } else {
      builder.swap().pop();
    }
  }
}
