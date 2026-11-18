package io.mutastic.engine;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.StoreInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// The test at the top of a for-each loop over an array. javac has no instruction for one: it
/// copies the array to a local of its own, its length to another, and counts a third up from 0,
/// reading each element at the top of the body:
///
/// ```
/// aload items; astore a; aload a; arraylength; istore n; iconst_0; istore i
/// top: iload i; iload n; if_icmpge end
///      aload a; iload i; iaload; istore x
///      … the body …
///      iinc i 1; goto top
/// end:
/// ```
///
/// None of the three locals has a name the source gives, so a card on the test would say `local6 <
/// local5`. And the index only ever goes from 0 up to the length by one: the element is read before
/// the body runs, so an index past the end throws before the test sees it.
final class ArrayForEach {
  /// What a for-each test works on, as its one operand: the array, as the source writes it, in the
  /// loop the source has. `…` for an array whose source can't be read back.
  static final String OPERAND = "for (… : %s)";

  private ArrayForEach() {}

  /// The slot javac copies the array to, when the jump at `code[at]` is a for-each loop's test.
  static OptionalInt arraySlot(List<CodeElement> code, int at) {
    if (!(code.get(at) instanceof BranchInstruction test) || test.opcode() != Opcode.IF_ICMPGE) {
      return OptionalInt.empty();
    }
    var loop = loopOf(code, at, test);
    if (loop == null) {
      return OptionalInt.empty();
    }
    var array = arrayOf(code, loop);
    if (array == null || !isJavacs(code, at, loop, array.slot())) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(array.slot());
  }

  /// The loop whose test is `test`, at `code[at]`, when it compares two `int` locals just after a
  /// label: the index and the length, at the loop's top.
  private static @Nullable Loop loopOf(List<CodeElement> code, int at, BranchInstruction test) {
    var compared = before(code, at, 2, true);
    var top = topOf(code, at);
    if (compared.size() < 2
        || top == null
        || !(compared.get(0) instanceof LoadInstruction index)
        || !(compared.get(1) instanceof LoadInstruction length)
        || !isInt(index)
        || !isInt(length)) {
      return null;
    }
    return new Loop(top, test.target(), index.slot(), length.slot());
  }

  /// Whether the loop, its array's copy in `array`, goes as javac's for-each does: the element read
  /// first, the index counted up by one at the end and stored to nowhere else, and none of the
  /// three locals a name the source gives.
  private static boolean isJavacs(List<CodeElement> code, int at, Loop loop, int array) {
    return readsTheElement(code, at, array, loop.index())
        && stepsAtTheEnd(code, at, loop)
        && keptInTheBody(code, at, loop, array)
        && !named(code, at, Set.of(array, loop.index(), loop.length()));
  }

  /// The operand of a for-each test, with the array as the source writes it, if it can be read.
  static String operand(@Nullable String array) {
    return OPERAND.formatted(array == null ? "…" : array);
  }

  /// The loop's top, where `goto` comes back to; its end, where the test jumps; and the index's and
  /// the length's slots.
  private record Loop(Label top, Label end, int index, int length) {}

  /// The array's copy, `astore a`, when the code before the loop's top is javac's: the copy, its
  /// length stored, and the index started at 0.
  private static @Nullable StoreInstruction arrayOf(List<CodeElement> code, Loop loop) {
    var setUp = before(code, labelAt(code, loop.top()), 6, false);
    if (setUp.size() < 6 || !(setUp.get(0) instanceof StoreInstruction copy)) {
      return null;
    }
    var slots = Set.of(copy.slot(), loop.index(), loop.length());
    boolean javacs =
        slots.size() == 3
            && copy.typeKind() == TypeKind.REFERENCE
            && loads(setUp.get(1), copy.slot(), TypeKind.REFERENCE)
            && setUp.get(2).opcode() == Opcode.ARRAYLENGTH
            && stores(setUp.get(3), loop.length())
            && setUp.get(4).opcode() == Opcode.ICONST_0
            && stores(setUp.get(5), loop.index());
    return javacs ? copy : null;
  }

  /// Whether the body starts by reading the element: `aload a; iload i; iaload`.
  private static boolean readsTheElement(List<CodeElement> code, int at, int array, int index) {
    var next = after(code, at, 3);
    return next.size() == 3
        && loads(next.get(0), array, TypeKind.REFERENCE)
        && loads(next.get(1), index, TypeKind.INT)
        && next.get(2) instanceof ArrayLoadInstruction;
  }

  /// Whether the loop ends `iinc i 1; goto top`, just before its end.
  private static boolean stepsAtTheEnd(List<CodeElement> code, int at, Loop loop) {
    int end = labelAt(code, loop.end());
    var last = before(code, end, 2, true);
    return end > at
        && last.size() == 2
        && last.get(0) instanceof IncrementInstruction step
        && step.slot() == loop.index()
        && step.constant() == 1
        && last.get(1) instanceof BranchInstruction back
        && back.opcode() == Opcode.GOTO
        && back.target().equals(loop.top());
  }

  /// Whether nothing in the loop stores to the array's copy, the length or the index, but the one
  /// step of the index at its end.
  private static boolean keptInTheBody(List<CodeElement> code, int at, Loop loop, int array) {
    var kept = Set.of(array, loop.length(), loop.index());
    int end = labelAt(code, loop.end());
    int steps = 0;
    for (int index = at + 1; index < end; index++) {
      switch (code.get(index)) {
        case StoreInstruction store when kept.contains(store.slot()) -> {
          return false;
        }
        case IncrementInstruction increment when kept.contains(increment.slot()) -> steps++;
        default -> {}
      }
    }
    return steps == 1;
  }

  private static boolean isInt(LoadInstruction load) {
    return load.typeKind() == TypeKind.INT;
  }

  /// Whether the class file names any of `slots` where the test is: then they're the source's own
  /// locals, not the ones javac makes for a for-each loop. A slot is used again in another scope,
  /// by another local, so only a name whose scope has the test counts.
  private static boolean named(List<CodeElement> code, int at, Set<Integer> slots) {
    return code.stream()
        .anyMatch(
            element ->
                element instanceof LocalVariable local
                    && slots.contains(local.slot())
                    && labelAt(code, local.startScope()) <= at
                    && at < labelAt(code, local.endScope()));
  }

  /// The label just before the instructions that load the test's operands: where the loop's `goto`
  /// comes back to.
  private static @Nullable Label topOf(List<CodeElement> code, int at) {
    int loads = 0;
    for (int index = at - 1; index >= 0 && loads <= 2; index--) {
      var element = code.get(index);
      if (element instanceof Instruction) {
        loads++;
      } else if (element instanceof LabelTarget target && loads == 2) {
        return target.label();
      }
    }
    return null;
  }

  private static int labelAt(List<CodeElement> code, Label label) {
    for (int index = 0; index < code.size(); index++) {
      if (code.get(index) instanceof LabelTarget target && target.label().equals(label)) {
        return index;
      }
    }
    return -1;
  }

  /// Up to `count` instructions just before `code[at]`, in order: stopping at a label when
  /// `toLabel`, as something else could jump there; otherwise past labels, as javac puts one where
  /// each of the source's locals starts.
  private static List<Instruction> before(
      List<CodeElement> code, int at, int count, boolean toLabel) {
    var found = new ArrayList<Instruction>();
    for (int index = at - 1; index >= 0 && found.size() < count; index--) {
      if (toLabel && code.get(index) instanceof LabelTarget) {
        break;
      }
      if (code.get(index) instanceof Instruction instruction) {
        found.addFirst(instruction);
      }
    }
    return found;
  }

  /// Up to `count` instructions just after `code[at]`, stopping at a label.
  private static List<Instruction> after(List<CodeElement> code, int at, int count) {
    var found = new ArrayList<Instruction>();
    for (int index = at + 1; index < code.size() && found.size() < count; index++) {
      if (code.get(index) instanceof LabelTarget) {
        break;
      }
      if (code.get(index) instanceof Instruction instruction) {
        found.add(instruction);
      }
    }
    return found;
  }

  private static boolean loads(Instruction instruction, int slot, TypeKind type) {
    return instruction instanceof LoadInstruction load
        && load.slot() == slot
        && load.typeKind() == type;
  }

  private static boolean stores(Instruction instruction, int slot) {
    return instruction instanceof StoreInstruction store
        && store.slot() == slot
        && store.typeKind() == TypeKind.INT;
  }
}
