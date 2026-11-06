package io.mutastic.engine;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.util.List;
import java.util.Optional;

/// The `&&` or `||` between two tests, for [io.mutastic.api.BuiltIn#LOGICAL_CONNECTOR].
///
/// There's no opcode for either: javac compiles `a && b` and `a || b` to a test of `a` that jumps,
/// then a test of `b` that jumps, with nothing jumping in between. The two shapes:
///
/// - **one target:** both jump to the same place, `L`. Control reaches `L` when either test's jump
///   is taken, and goes on past the second only when neither is. `if (a && b)` is this shape, both
///   jumping past the body when their test fails.
/// - **past the second:** the first jumps to just after the second, the second to `L`. Control
///   reaches `L` only when the first's jump isn't taken and the second's is. `if (a || b)` is this
///   shape: `a` jumps into the body, `b` past it.
///
/// Swapping the connector turns one shape into the other: the first jump goes the other way, to
/// just past the second (from one target) or to `L` (from past the second). Taken when either is
/// becomes taken when both are, and the other way round, so whatever the source says, its `&&`
/// becomes `||` or its `||` `&&`: through a `!`, javac flips both tests and both targets, and the
/// connector stays a connector.
///
/// A pair is only taken when nothing but plain instructions comes between the two jumps: no label
/// (other code could jump in), no `return`, `throw` or `switch`, and no line of its own (a nested
/// `if` on the next line compiles the same as `&&`, but its source has no connector to change).
final class Connectors {
  /// The label of the change, in every connector mutant's id.
  static final String SWAPPED = "&& and || swapped";

  private Connectors() {}

  /// Two tests joined by a connector, the first at `first` in the code.
  ///
  /// @param second where the second test's jump is
  /// @param oneTarget both jump to the same place; otherwise the first jumps to just past the
  ///   second
  record Pair(int first, int second, boolean oneTarget) {}

  /// The pair whose first test is the jump at `code[at]`, if it's one, and the whole condition.
  static Optional<Pair> at(List<CodeElement> code, int at) {
    if (!(code.get(at) instanceof BranchInstruction first)
        || !Operators.isConditional(first)
        || !startsAStatement(code, at)) {
      return Optional.empty();
    }
    return next(code, at)
        .flatMap(second -> pair(code, at, second))
        .filter(pair -> !anotherTest(code, pair));
  }

  /// The second test's jump: the next one, with only plain instructions on the same line between.
  private static Optional<Integer> next(List<CodeElement> code, int at) {
    int line = lineAt(code, at);
    for (int index = at + 1; index < code.size(); index++) {
      switch (code.get(index)) {
        case BranchInstruction second -> {
          return Operators.isConditional(second) ? Optional.of(index) : Optional.empty();
        }
        case LabelTarget _ -> {
          return Optional.empty();
        }
        case LineNumber number when number.line() != line -> {
          return Optional.empty();
        }
        // A store between the tests is what javac writes for `x instanceof Point p && p.x() > 0`:
        // the second test reads a local only the first one's passing sets, so `||` can't run it.
        case Instruction instruction when endsTheTests(instruction) || stores(instruction) -> {
          return Optional.empty();
        }
        default -> {}
      }
    }
    return Optional.empty();
  }

  private static Optional<Pair> pair(List<CodeElement> code, int at, int index) {
    var first = (BranchInstruction) code.get(at);
    var second = (BranchInstruction) code.get(index);
    if (boundAfter(code, index, second.target())) {
      return Optional.empty();
    }
    if (first.target().equals(second.target())) {
      return Optional.of(new Pair(at, index, true));
    }
    return boundAfter(code, index, first.target())
        ? Optional.of(new Pair(at, index, false))
        : Optional.empty();
  }

  /// Whether the first test starts its condition: going back from its jump, a line number or a
  /// label only a `goto` jumps to (a loop's test, a join) comes before any other label or jump.
  /// Otherwise an earlier test may be part of the same condition.
  private static boolean startsAStatement(List<CodeElement> code, int at) {
    for (int index = at - 1; index >= 0; index--) {
      switch (code.get(index)) {
        case LineNumber _ -> {
          return true;
        }
        case LabelTarget target -> {
          return !testedJumpsTo(code, target.label());
        }
        case BranchInstruction _ -> {
          return false;
        }
        case Instruction instruction when endsTheTests(instruction) -> {
          return false;
        }
        default -> {}
      }
    }
    return false;
  }

  /// Whether any test in the code jumps to `label`.
  private static boolean testedJumpsTo(List<CodeElement> code, Label label) {
    return code.stream()
        .anyMatch(
            element ->
                element instanceof BranchInstruction branch
                    && Operators.isConditional(branch)
                    && branch.target().equals(label));
  }

  /// Whether either way out of the pair is a third test of the same condition: on the same line,
  /// plain instructions and then a jump that goes to the other way out, or on past it.
  private static boolean anotherTest(List<CodeElement> code, Pair pair) {
    var second = (BranchInstruction) code.get(pair.second());
    int past = firstInstruction(code, pair.second() + 1);
    int jumped = firstInstruction(code, where(code, second.target()));
    return testsFrom(code, pair, past, jumped) || testsFrom(code, pair, jumped, past);
  }

  /// Whether the code from `start` tests something other than the pair and then goes to `other`,
  /// either way.
  private static boolean testsFrom(List<CodeElement> code, Pair pair, int start, int other) {
    int line = lineAt(code, pair.first());
    for (int index = start; index >= 0 && index < code.size(); index++) {
      switch (code.get(index)) {
        case BranchInstruction test -> {
          // A loop back to the pair itself, as a `do`'s test at the bottom is, isn't another.
          return index != pair.first()
              && Operators.isConditional(test)
              && (firstInstruction(code, where(code, test.target())) == other
                  || firstInstruction(code, index + 1) == other);
        }
        case LabelTarget _ -> {
          return false;
        }
        case LineNumber number when number.line() != line -> {
          return false;
        }
        case Instruction instruction when endsTheTests(instruction) -> {
          return false;
        }
        default -> {}
      }
    }
    return false;
  }

  /// Where `label` is bound in the code, or -1.
  private static int where(List<CodeElement> code, Label label) {
    for (int index = 0; index < code.size(); index++) {
      if (code.get(index) instanceof LabelTarget target && target.label().equals(label)) {
        return index;
      }
    }
    return -1;
  }

  /// The first instruction at or after `from`, or -1.
  private static int firstInstruction(List<CodeElement> code, int from) {
    for (int index = Math.max(from, 0); from >= 0 && index < code.size(); index++) {
      if (code.get(index) instanceof Instruction) {
        return index;
      }
    }
    return -1;
  }

  private static boolean stores(Instruction instruction) {
    return instruction instanceof java.lang.classfile.instruction.StoreInstruction
        || instruction instanceof java.lang.classfile.instruction.IncrementInstruction;
  }

  /// An instruction no test of a condition has.
  private static boolean endsTheTests(Instruction instruction) {
    return instruction instanceof ReturnInstruction
        || instruction instanceof ThrowInstruction
        || instruction instanceof TableSwitchInstruction
        || instruction instanceof LookupSwitchInstruction;
  }

  /// Whether `label` is where the code goes on after `code[at]`: bound before the next instruction.
  static boolean boundAfter(List<CodeElement> code, int at, Label label) {
    for (int index = at + 1; index < code.size(); index++) {
      var element = code.get(index);
      if (element instanceof LabelTarget target && target.label().equals(label)) {
        return true;
      }
      if (element instanceof Instruction) {
        return false;
      }
    }
    return false;
  }

  /// The line the code at `at` is on, or -1 before the first line number.
  private static int lineAt(List<CodeElement> code, int at) {
    for (int index = at; index >= 0; index--) {
      if (code.get(index) instanceof LineNumber number) {
        return number.line();
      }
    }
    return -1;
  }

  /// The jump that tests the opposite: `IFNE` for `IFEQ`.
  static java.lang.classfile.Opcode negated(java.lang.classfile.Opcode opcode) {
    var jump = Comparisons.Jump.of(opcode);
    return jump.family().opcode(jump.relation().negated());
  }
}
