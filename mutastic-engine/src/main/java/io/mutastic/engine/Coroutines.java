package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Operator;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/// The instructions kotlinc writes to make a function suspend: a state machine around the person's
/// code. Its checks fail only if the coroutine library breaks its promises, so a mutant of one asks
/// for a test of code nobody wrote; what the person wrote in the same method keeps its mutants.
///
/// Told from the person's code by what's in the bytecode, starting from a method that's a suspend
/// function (a `Continuation` last, or `invokeSuspend` of a class with `@DebugMetadata`) and reads
/// `COROUTINE_SUSPENDED`:
///
/// - what comes before the `switch` on `label`, and the `switch`: which continuation, and where to
///   resume
/// - the throw for a `label` nothing set, with kotlinc's own message
/// - `throwOnFailure` of the local kotlinc calls `$result`
/// - a comparison with `COROUTINE_SUSPENDED`, and its return
/// - saving the locals to the continuation's `I$0`, `L$0` and the like, and setting `label`, and
///   reading them back
/// - the `Unit` a suspend function returns when it ends: nothing in the source says to, and a
///   caller can't see it
final class Coroutines {
  private static final String CONTINUATION = "Lkotlin/coroutines/Continuation;";
  private static final String DEBUG_METADATA = "Lkotlin/coroutines/jvm/internal/DebugMetadata;";
  private static final String INTRINSICS = "kotlin/coroutines/intrinsics/IntrinsicsKt";
  private static final String UNSET_LABEL = "call to 'resume' before 'invoke' with coroutine";

  /// `I$0`, `L$1`: where kotlinc saves a local across a suspension.
  private static final Pattern SAVED = Pattern.compile("[A-Z]\\$\\d+");

  /// Operators whose change is to the method as a whole, made at its first instruction wherever
  /// that is: they aren't about that instruction.
  private static final Set<Operator> WHOLE_METHOD =
      Set.of(BuiltIn.REPLACE_BODY, BuiltIn.NULL_PARAMETER);

  static final String REASON = "generated: kotlinc's coroutine state, not this function's logic";

  private final List<Instruction> instructions = new ArrayList<>();

  /// Where each instruction is in the code. A place, not the instruction: one with no operands, an
  /// `areturn` or an `isub`, is the same object wherever it's used.
  private final List<Integer> places = new ArrayList<>();

  private final Set<Integer> machinery = new HashSet<>();

  private Coroutines(List<CodeElement> code) {
    for (int at = 0; at < code.size(); at++) {
      if (code.get(at) instanceof Instruction instruction) {
        instructions.add(instruction);
        places.add(at);
      }
    }
  }

  /// Whether `operator` changing the instruction at this place in the code would be changing the
  /// machinery.
  static boolean changes(Operator operator, Set<Integer> machinery, int place) {
    return machinery.contains(place) && !WHOLE_METHOD.contains(operator);
  }

  /// The places in the code of the method's machinery: none in a method that isn't a suspend
  /// function.
  static Set<Integer> machinery(ClassModel model, MethodModel method, List<CodeElement> code) {
    boolean reads =
        code.stream()
            .anyMatch(
                element ->
                    element instanceof Instruction instruction && readsSuspended(instruction));
    if (!reads || !isSuspendFunction(model, method)) {
      return Set.of();
    }
    var found = new Coroutines(code);
    found.read(resultSlots(method));
    return found.machinery;
  }

  private void mark(int index) {
    machinery.add(places.get(index));
  }

  private static boolean isSuspendFunction(ClassModel model, MethodModel method) {
    var parameters = method.methodTypeSymbol().parameterList();
    boolean takesAContinuation =
        !parameters.isEmpty() && parameters.getLast().descriptorString().equals(CONTINUATION);
    return takesAContinuation
        || (method.methodName().equalsString("invokeSuspend") && hasDebugMetadata(model));
  }

  private static boolean hasDebugMetadata(ClassModel model) {
    return model
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .filter(
            annotations ->
                annotations.annotations().stream()
                    .anyMatch(annotation -> annotation.className().equalsString(DEBUG_METADATA)))
        .isPresent();
  }

  /// The slots of the local kotlinc calls `$result`, which holds what the last suspension gave.
  private static Set<Integer> resultSlots(MethodModel method) {
    return method
        .code()
        .flatMap(code -> code.findAttribute(Attributes.localVariableTable()))
        .stream()
        .flatMap(table -> table.localVariables().stream())
        .filter(variable -> variable.name().equalsString("$result"))
        .map(variable -> variable.slot())
        .collect(java.util.stream.Collectors.toSet());
  }

  private void read(Set<Integer> resultSlots) {
    int suspended = -1;
    for (int at = 0; at < instructions.size(); at++) {
      var instruction = instructions.get(at);
      if (readsSuspended(instruction) && at(at + 1) instanceof StoreInstruction store) {
        suspended = store.slot();
      }
      readComparison(at, suspended);
      readState(at);
      readResult(at, resultSlots);
    }
  }

  private Instruction at(int index) {
    return index >= 0 && index < instructions.size()
        ? instructions.get(index)
        : instructions.getFirst();
  }

  /// `dup; aload suspended; if_acmpne`, then `aload suspended; areturn`. A tail call has no local
  /// and no state: `dup; COROUTINE_SUSPENDED; if_acmpne`, then `areturn`. And the `Unit` at the
  /// end.
  private void readComparison(int index, int suspended) {
    var instruction = instructions.get(index);
    boolean afterLoad = isLoadOf(at(index - 1), suspended);
    if (afterLoad && instruction instanceof ReturnInstruction) {
      mark(index);
    }
    if (instruction instanceof BranchInstruction branch && isSameObject(branch)) {
      if (afterLoad) {
        mark(index);
      }
      readTailCall(index);
    }
    if (isUnit(instruction) && at(index + 1) instanceof ReturnInstruction) {
      mark(index + 1);
    }
  }

  private void readTailCall(int index) {
    if (readsSuspended(at(index - 1)) && at(index - 2).opcode() == Opcode.DUP) {
      mark(index);
      if (at(index + 1) instanceof ReturnInstruction) {
        mark(index + 1);
      }
    }
  }

  private static boolean isSameObject(BranchInstruction branch) {
    return branch.opcode() == Opcode.IF_ACMPEQ || branch.opcode() == Opcode.IF_ACMPNE;
  }

  private static boolean isLoadOf(Instruction instruction, int slot) {
    return slot >= 0 && instruction instanceof LoadInstruction load && load.slot() == slot;
  }

  private static boolean isUnit(Instruction instruction) {
    return instruction instanceof FieldInstruction field
        && field.opcode() == Opcode.GETSTATIC
        && field.owner().asInternalName().equals("kotlin/Unit");
  }

  /// The way in, the throw for an unset `label`, and saving and restoring across a suspension.
  private void readState(int index) {
    var instruction = instructions.get(index);
    if (isSwitch(instruction) && isLabel(at(index - 1))) {
      machinery.addAll(places.subList(0, index + 1));
    }
    if (instruction instanceof ConstantInstruction constant
        && UNSET_LABEL.equals(constant.constantValue())) {
      mark(index);
      for (int after = index + 1; after < instructions.size(); after++) {
        mark(after);
        if (instructions.get(after).opcode() == Opcode.ATHROW) {
          break;
        }
      }
    }
    if (instruction instanceof FieldInstruction field) {
      readField(index, field);
    }
  }

  private void readField(int index, FieldInstruction field) {
    if (field.opcode() == Opcode.PUTFIELD && (isLabel(field) || isSaved(field))) {
      mark(index);
      if (isLabel(field) && at(index - 1) instanceof ConstantInstruction) {
        mark(index - 1);
      }
    }
    if (field.opcode() == Opcode.GETFIELD && isSaved(field)) {
      int next = at(index + 1).opcode() == Opcode.CHECKCAST ? index + 2 : index + 1;
      if (at(next) instanceof StoreInstruction) {
        mark(next);
      }
    }
  }

  private static boolean isSwitch(Instruction instruction) {
    return instruction instanceof TableSwitchInstruction
        || instruction.opcode() == Opcode.LOOKUPSWITCH;
  }

  private static boolean isLabel(Instruction instruction) {
    return instruction instanceof FieldInstruction field
        && field.name().equalsString("label")
        && field.typeSymbol().descriptorString().equals("I");
  }

  private static boolean isSaved(FieldInstruction field) {
    return SAVED.matcher(field.name().stringValue()).matches();
  }

  /// `aload $result; ResultKt.throwOnFailure`: not a `Result` of the person's, which is their own.
  private void readResult(int index, Set<Integer> resultSlots) {
    if (instructions.get(index) instanceof InvokeInstruction call
        && call.owner().asInternalName().equals("kotlin/ResultKt")
        && call.name().equalsString("throwOnFailure")
        && at(index - 1) instanceof LoadInstruction load
        && resultSlots.contains(load.slot())) {
      mark(index);
    }
  }

  private static boolean readsSuspended(Instruction instruction) {
    return instruction instanceof InvokeInstruction call
        && call.owner().asInternalName().equals(INTRINSICS)
        && call.name().equalsString("getCOROUTINE_SUSPENDED");
  }
}
