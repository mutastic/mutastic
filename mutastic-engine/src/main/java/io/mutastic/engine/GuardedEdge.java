package io.mutastic.engine;

import io.mutastic.api.Operator;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/// An equivalent mutant of a comparison only reached where its two sides differ.
///
/// In `if (a != b) { return a < b; }`, `a < b` and `a <= b` give the same answer unless `a == b`,
/// which the `if` around it rules out. The bytecode shows it: an `IF_ICMPEQ` on the same two `int`
/// locals (or `LCMP; IFEQ` on two `long`s) falls through into code no other jump enters and that
/// stores to neither, ending at the comparison; or an `IF_ICMPNE` jumps to a label only it reaches,
/// after code that never runs on into it (`if (a == b) return 0;`). Anything else isn't judged: a
/// field, which can change between reads, or a `float` or `double`, whose `NaN` isn't equal to
/// itself.
final class GuardedEdge {
  private static final Set<Opcode> EQUALITY =
      Set.of(Opcode.IF_ICMPEQ, Opcode.IF_ICMPNE, Opcode.IFEQ, Opcode.IFNE);

  private final List<CodeElement> code;
  private final int at;
  private final Compared compared;
  private final Map<Label, Integer> positions = new HashMap<>();

  /// Where each label is jumped to from, by position; a handler's from `-1`, as if from outside.
  private final Map<Label, List<Integer>> sources = new HashMap<>();

  /// Two locals compared, `ILOAD a; ILOAD b; IF_ICMPLT` or `LLOAD a; LLOAD b; LCMP; IFLT`, the
  /// first load at `start`.
  private record Compared(int first, int second, boolean wide, int start) {
    boolean same(Compared other) {
      return wide == other.wide
          && ((first == other.first && second == other.second)
              || (first == other.second && second == other.first));
    }

    /// Whether a store to `size` slots from `slot` overwrites either.
    boolean overwrittenBy(int slot, int size) {
      int width = wide ? 2 : 1;
      return (slot < first + width && first < slot + size)
          || (slot < second + width && second < slot + size);
    }
  }

  private GuardedEdge(List<CodeElement> code, int at, Compared compared) {
    this.code = code;
    this.at = at;
    this.compared = compared;
    for (int index = 0; index < code.size(); index++) {
      switch (code.get(index)) {
        case LabelTarget target -> positions.put(target.label(), index);
        case BranchInstruction branch -> from(index, branch.target());
        case TableSwitchInstruction table -> from(index, table.defaultTarget(), table.cases());
        case LookupSwitchInstruction lookup -> from(index, lookup.defaultTarget(), lookup.cases());
        case ExceptionCatch handler -> from(-1, handler.handler());
        default -> {}
      }
    }
  }

  private void from(int index, Label target, List<SwitchCase> cases) {
    from(index, target);
    cases.forEach(each -> from(index, each.target()));
  }

  private void from(int index, Label target) {
    sources.computeIfAbsent(target, _ -> new ArrayList<>()).add(index);
  }

  /// Why the mutant `operator` makes, `replacement`, at `code[at]` shouldn't run, if it differs
  /// only where the two sides are equal and an `if` that they differ comes first.
  static Optional<String> reason(
      Operator operator, String replacement, List<CodeElement> code, int at) {
    if (!Comparisons.changesAComparison(operator)
        || !(code.get(at) instanceof BranchInstruction branch)
        || !onlyAtEquality(branch.opcode(), replacement)) {
      return Optional.empty();
    }
    return compared(code, at).flatMap(compared -> new GuardedEdge(code, at, compared).guarded());
  }

  /// Whether the changed jump gives the original's answer wherever the two sides differ.
  private static boolean onlyAtEquality(Opcode original, String replacement) {
    var changed =
        Stream.of(Opcode.values())
            .filter(opcode -> opcode.kind() == Opcode.Kind.BRANCH)
            .filter(opcode -> opcode.name().equals(replacement) && opcode != original)
            .findFirst();
    if (changed.isEmpty()) {
      return false;
    }
    var before = Comparisons.Jump.of(original).relation();
    var after = Comparisons.Jump.of(changed.get()).relation();
    return before.holds(-1) == after.holds(-1) && before.holds(1) == after.holds(1);
  }

  /// The two locals the jump at `jump` compares, read just before it with no label in between.
  private static Optional<Compared> compared(List<CodeElement> code, int jump) {
    var before = indexesBefore(code, jump, 3);
    boolean wide =
        !before.isEmpty() && ((Instruction) code.get(before.getLast())).opcode() == Opcode.LCMP;
    var loads = wide ? before.subList(0, before.size() - 1) : before;
    var opcode = ((Instruction) code.get(jump)).opcode().name();
    if (loads.size() < 2 || !(wide ? opcode.matches("IF[A-Z]{2}") : opcode.startsWith("IF_ICMP"))) {
      return Optional.empty();
    }
    int start = loads.get(loads.size() - 2);
    var kind = wide ? "LLOAD" : "ILOAD";
    var one = slotLoaded(code.get(start), kind);
    var other = slotLoaded(code.get(loads.getLast()), kind);
    return one.isPresent() && other.isPresent() && !one.equals(other)
        ? Optional.of(new Compared(one.get(), other.get(), wide, start))
        : Optional.empty();
  }

  /// The slot `element` loads, if it's a load of `kind`: `ILOAD` or `LLOAD`.
  private static Optional<Integer> slotLoaded(CodeElement element, String kind) {
    return element instanceof LoadInstruction load && load.opcode().name().startsWith(kind)
        ? Optional.of(load.slot())
        : Optional.empty();
  }

  /// Where the `count` instructions before `code[at]` are, oldest first; fewer at a label.
  private static List<Integer> indexesBefore(List<CodeElement> code, int at, int count) {
    var found = new ArrayList<Integer>();
    for (int index = at - 1; index >= 0 && found.size() < count; index--) {
      if (code.get(index) instanceof LabelTarget) {
        break;
      }
      if (code.get(index) instanceof Instruction) {
        found.addFirst(index);
      }
    }
    return found;
  }

  /// The reason, from the first `if` before the comparison that guards it.
  private Optional<String> guarded() {
    for (int guard = 0; guard < compared.start(); guard++) {
      if (code.get(guard) instanceof BranchInstruction test
          && EQUALITY.contains(test.opcode())
          && compared(code, guard).filter(compared::same).isPresent()) {
        boolean jumpsWhenEqual = test.opcode() == Opcode.IF_ICMPEQ || test.opcode() == Opcode.IFEQ;
        var entry = jumpsWhenEqual ? Optional.of(guard) : onlyWayTo(guard, test.target());
        if (entry.filter(this::onlyWayIn).isPresent()) {
          return Optional.of(why(jumpsWhenEqual));
        }
      }
    }
    return Optional.empty();
  }

  /// Where `target` is, if the jump at `guard` is the only way there: nothing else jumps to it, and
  /// the code before it doesn't run on into it.
  private Optional<Integer> onlyWayTo(int guard, Label target) {
    int entry = positions.getOrDefault(target, -1);
    boolean onlyJump = sources.getOrDefault(target, List.of()).equals(List.of(guard));
    return entry > guard && onlyJump && !fallsInto(entry) ? Optional.of(entry) : Optional.empty();
  }

  /// Whether something runs on into the label at `entry`: it isn't just after a `goto`, `return` or
  /// `throw`.
  private boolean fallsInto(int entry) {
    for (int index = entry - 1; index >= 0; index--) {
      if (code.get(index) instanceof LabelTarget) {
        return true;
      }
      if (code.get(index) instanceof Instruction instruction) {
        return !(instruction instanceof ReturnInstruction
            || instruction instanceof ThrowInstruction
            || instruction.opcode() == Opcode.GOTO
            || instruction.opcode() == Opcode.GOTO_W);
      }
    }
    return true;
  }

  /// Whether the code from `entry` to the comparison is entered only at `entry`, every label in it
  /// jumped to only from within it and none a handler's, and stores to neither local.
  private boolean onlyWayIn(int entry) {
    if (entry > compared.start()) {
      return false;
    }
    for (int index = entry + 1; index < at; index++) {
      boolean enteredFromOutside =
          code.get(index) instanceof LabelTarget target
              && sources.getOrDefault(target.label(), List.of()).stream()
                  .anyMatch(source -> source <= entry || source >= at);
      if (enteredFromOutside || overwrites(code.get(index))) {
        return false;
      }
    }
    return true;
  }

  private boolean overwrites(CodeElement element) {
    return switch (element) {
      case StoreInstruction store ->
          compared.overwrittenBy(store.slot(), store.typeKind().slotSize());
      case IncrementInstruction increment -> compared.overwrittenBy(increment.slot(), 1);
      default -> false;
    };
  }

  /// The two-line reason, with the locals' names where the debug table has them.
  private String why(boolean around) {
    var one = name(compared.first());
    var other = name(compared.second());
    if (one.isEmpty() || other.isEmpty()) {
      return "equivalent: differs only when both sides are the same. That can't happen because"
          + (around
              ? " the if around it already checked they differ."
              : " the if before it goes elsewhere when they're the same.");
    }
    var left = one.get();
    var right = other.get();
    return "equivalent: differs only when %s is %s. That can't happen because %s"
        .formatted(
            left,
            right,
            around
                ? "the if %s != %s around it rules it out.".formatted(left, right)
                : "the if %s == %s before it goes elsewhere.".formatted(left, right));
  }

  /// The source name of the local in `slot` at the comparison; none for javac's own or without the
  /// table.
  private Optional<String> name(int slot) {
    return code.stream()
        .flatMap(element -> element instanceof LocalVariable local ? Stream.of(local) : Stream.of())
        .filter(local -> local.slot() == slot)
        .filter(
            local ->
                positions.getOrDefault(local.startScope(), Integer.MAX_VALUE) <= at
                    && at < positions.getOrDefault(local.endScope(), -1))
        .map(local -> local.name().stringValue())
        .filter(name -> !name.contains("$"))
        .findFirst();
  }
}
