package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Operator;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// An equivalent mutant of an `||`: a test whose case the next test already covers.
///
/// In `if (inCredit(balance) || balance > -1000)`, where `inCredit` is `balance >= 1`, every
/// balance the first test lets into the body, the second does too. Forcing the first false changes
/// nothing, and no test can tell; an agent once spent turns proving it. So the mutant is skipped,
/// as is a boundary of the first test whose new edge the second still covers.
///
/// The bytecode shows the `||` itself, whatever the source: the first test jumps to the body's
/// label when it holds; the second, just before that label, jumps past the body when it doesn't.
/// Both compare the same `int` local with a constant, or the first calls a static method of this
/// class whose whole body is such a comparison of its parameter. Anything else isn't judged.
final class ImpliedOr {
  private ImpliedOr() {}

  /// The `int` values a test holds for, from `low` to `high`.
  record Range(long low, long high) {
    boolean within(Range other) {
      return low >= other.low && high <= other.high;
    }
  }

  /// A test of a local: the values of slot `slot` it holds for.
  record Test(int slot, Range holds) {}

  /// Where a mutant is: its class, for the methods a test calls, and its method's code.
  record Site(ClassModel owner, List<CodeElement> code, int at) {}

  /// Why the mutant shouldn't run, if the test it changes is covered by the next one.
  static Optional<String> reason(Operator operator, String replacement, Site site) {
    boolean forcedFalse =
        operator == BuiltIn.REMOVE_CONDITIONAL && replacement.equals("never jump");
    if (!(forcedFalse || operator == BuiltIn.BOUNDARY)
        || !(site.code().get(site.at()) instanceof BranchInstruction first)) {
      return Optional.empty();
    }
    var tested = firstTest(site, first);
    var next = secondTest(site, first.target());
    if (tested.isEmpty() || next.isEmpty() || tested.get().slot() != next.get().slot()) {
      return Optional.empty();
    }
    var covered = next.get().holds();
    return tested.get().holds().within(covered)
            && edgeCovered(forcedFalse, replacement, site, covered)
        ? Optional.of("equivalent: the next test of the `||` already covers its case")
        : Optional.empty();
  }

  /// Forced false, the first test lets nothing in; moved to a new edge, what it lets in then must
  /// be covered by the next test too.
  private static boolean edgeCovered(
      boolean forcedFalse, String replacement, Site site, Range covered) {
    return forcedFalse
        || range(condition(replacement), constantBefore(site.code(), site.at()))
            .filter(moved -> moved.within(covered))
            .isPresent();
  }

  /// What the jump tests when it jumps into the body: `x > 5` from `load x; push 5; IF_ICMPGT`, `x
  /// > 0` from `load x; IFGT`, or the comparison a called `inCredit(x)` makes, for `IFNE` after it.
  private static Optional<Test> firstTest(Site site, BranchInstruction first) {
    var before = instructionsBefore(site.code(), site.at(), 2);
    var opcode = first.opcode().name();
    if (opcode.startsWith("IF_ICMP")) {
      return before.size() == 2 && before.get(0) instanceof LoadInstruction load
          ? range(condition(opcode), constantBefore(site.code(), site.at()))
              .map(holds -> new Test(load.slot(), holds))
          : Optional.empty();
    }
    if (before.size() == 2
        && before.get(0) instanceof LoadInstruction load
        && before.get(1) instanceof InvokeInstruction call
        && first.opcode() == Opcode.IFNE) {
      return ComparisonMethod.of(site.owner(), call).map(holds -> new Test(load.slot(), holds));
    }
    return !before.isEmpty() && before.getLast() instanceof LoadInstruction load
        ? withZero(opcode).map(holds -> new Test(load.slot(), holds))
        : Optional.empty();
  }

  /// `x > 0` for `IFGT`: a jump that compares an `int` with zero; empty for any other.
  private static Optional<Range> withZero(String opcode) {
    return opcode.length() == 4 && opcode.startsWith("IF")
        ? range(condition(opcode), Optional.of(0))
        : Optional.empty();
  }

  /// The test just before `body`, the label the first jumps to: `load x; push c; if not B goto
  /// past; body:`, or `load x; if not B goto past; body:` against zero. What it lets into the body
  /// is `B`, the opposite of its jump.
  private static Optional<Test> secondTest(Site site, Label body) {
    var next = significantAfter(site.code(), site.at(), 4);
    if (next.size() < 3 || !(next.get(0) instanceof LoadInstruction load)) {
      return Optional.empty();
    }
    if (next.get(1) instanceof BranchInstruction second && startsBody(next.get(2), second, body)) {
      return withZero(second.opcode().name())
          .flatMap(_ -> range(negated(condition(second.opcode().name())), Optional.of(0)))
          .map(holds -> new Test(load.slot(), holds));
    }
    if (next.size() == 4
        && next.get(2) instanceof BranchInstruction second
        && second.opcode().name().startsWith("IF_ICMP")
        && startsBody(next.get(3), second, body)) {
      return range(negated(condition(second.opcode().name())), constantBefore(next, 2))
          .map(holds -> new Test(load.slot(), holds));
    }
    return Optional.empty();
  }

  /// The body starts right after the second test, which jumps past it.
  private static boolean startsBody(CodeElement after, BranchInstruction second, Label body) {
    return after instanceof LabelTarget target
        && target.label().equals(body)
        && !second.target().equals(body);
  }

  /// The next `count` instructions and labels after `code[at]`.
  private static List<CodeElement> significantAfter(List<CodeElement> code, int at, int count) {
    var next = new ArrayList<CodeElement>();
    for (int index = at + 1; index < code.size() && next.size() < count; index++) {
      if (code.get(index) instanceof Instruction || code.get(index) instanceof LabelTarget) {
        next.add(code.get(index));
      }
    }
    return next;
  }

  /// The `int` constant pushed just before `code[at]`.
  private static Optional<Integer> constantBefore(List<CodeElement> code, int at) {
    var before = instructionsBefore(code, at, 1);
    return before.size() == 1
            && before.getFirst() instanceof ConstantInstruction constant
            && constant.constantValue() instanceof Integer value
        ? Optional.of(value)
        : Optional.empty();
  }

  /// The `count` instructions before `code[at]`, oldest first; fewer at a label, where other code
  /// can jump in.
  static List<Instruction> instructionsBefore(List<CodeElement> code, int at, int count) {
    var found = new ArrayList<Instruction>();
    for (int index = at - 1; index >= 0 && found.size() < count; index--) {
      if (code.get(index) instanceof LabelTarget) {
        break;
      }
      if (code.get(index) instanceof Instruction instruction) {
        found.addFirst(instruction);
      }
    }
    return found;
  }

  /// `GT` for `IF_ICMPGT` or `IFGT`.
  static String condition(String opcode) {
    return opcode.substring(opcode.length() - 2);
  }

  static String negated(String condition) {
    return switch (condition) {
      case "GT" -> "LE";
      case "LE" -> "GT";
      case "GE" -> "LT";
      case "LT" -> "GE";
      case "EQ" -> "NE";
      default -> "EQ";
    };
  }

  /// The values `x CONDITION constant` holds for; empty for `!=`, which isn't one range.
  static Optional<Range> range(String condition, Optional<Integer> constant) {
    return constant.flatMap(
        value ->
            switch (condition) {
              case "GT" -> Optional.of(new Range(value + 1L, Integer.MAX_VALUE));
              case "GE" -> Optional.of(new Range(value, Integer.MAX_VALUE));
              case "LT" -> Optional.of(new Range(Integer.MIN_VALUE, value - 1L));
              case "LE" -> Optional.of(new Range(Integer.MIN_VALUE, value));
              case "EQ" -> Optional.of(new Range(value, value));
              default -> Optional.empty();
            });
  }
}
