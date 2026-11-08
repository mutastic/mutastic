package io.mutastic.engine;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.ReturnInstruction;
import java.util.List;
import java.util.Optional;

/// The checks in a Kotlin method, told apart by who wrote them.
///
/// kotlinc's own: `Intrinsics.checkNotNullParameter` and its like fail only if a caller outside
/// Kotlin breaks a promise the types make, and a `lateinit`'s check says what's wrong with a read
/// the type system lets through. No line of the source asks for either.
///
/// The person's: `x!!` and `x as String` are written in the source, and compile to
/// `Intrinsics.checkNotNull`, with a message in the second. Take one away and a `null` carries on
/// where they said it shouldn't, which a test can see: so those run. The exception is `x!!.size`,
/// where the very next step reads the value: without the check that step throws the same
/// `NullPointerException`, and nothing runs between them, so the mutant can't be told apart.
final class KotlinChecks {
  private static final String INTRINSICS = "kotlin/jvm/internal/Intrinsics";
  private static final String UNINITIALIZED = "throwUninitializedPropertyAccessException";

  private KotlinChecks() {}

  /// Why the instruction at `code[at]` shouldn't have mutants, if it's part of one of kotlinc's own
  /// checks or a `!!` that makes no difference.
  static Optional<String> reason(List<CodeElement> code, int at) {
    var element = code.get(at);
    if (element instanceof InvokeInstruction call) {
      return reasonForCall(call, code, at);
    }
    if (element instanceof ConstantInstruction
        && nextInstruction(code, at).filter(KotlinChecks::isInserted).isPresent()) {
      return Optional.of("generated: the name kotlinc's own check reports");
    }
    if (element instanceof ReturnInstruction returning
        && returning.opcode() == Opcode.ARETURN
        && previousInstruction(code, at).filter(KotlinChecks::isUnit).isPresent()) {
      return Optional.of(UNIT);
    }
    return element instanceof BranchInstruction branch && guardsLateinit(code, at, branch)
        ? Optional.of(LATEINIT)
        : Optional.empty();
  }

  private static final String LATEINIT =
      "generated: kotlinc's check that a `lateinit` property has been set";

  /// A lambda or function that returns nothing gives back `Unit` in the class file, and no caller
  /// reads it: a `null` there changes nothing a test can see.
  private static final String UNIT =
      "generated: the `Unit` kotlinc returns where the source returns nothing";

  /// `getstatic kotlin/Unit.INSTANCE`.
  private static boolean isUnit(Instruction instruction) {
    return instruction instanceof java.lang.classfile.instruction.FieldInstruction field
        && field.opcode() == Opcode.GETSTATIC
        && field.owner().asInternalName().equals("kotlin/Unit")
        && field.name().equalsString("INSTANCE");
  }

  /// The instruction just before `code[at]`, when no jump lands between them.
  private static Optional<Instruction> previousInstruction(List<CodeElement> code, int at) {
    for (int before = at - 1; before >= 0; before--) {
      switch (code.get(before)) {
        case Instruction instruction -> {
          return Optional.of(instruction);
        }
        case LabelTarget _ -> {
          return Optional.empty();
        }
        default -> {}
      }
    }
    return Optional.empty();
  }

  private static Optional<String> reasonForCall(
      InvokeInstruction call, List<CodeElement> code, int at) {
    if (isUninitialized(call)) {
      return Optional.of(LATEINIT);
    }
    if (isInserted(call)) {
      return Optional.of("generated: kotlinc's own check on a value its types already promise");
    }
    return isBang(call) && nextInstruction(code, at).filter(KotlinChecks::reads).isPresent()
        ? Optional.of("equivalent: the next step throws the same NullPointerException")
        : Optional.empty();
  }

  /// One of the checks kotlinc writes in. Not `checkNotNull`, which is `!!` or `as`, or
  /// `Intrinsics.areEqual`, which is `==`.
  static boolean isInserted(Instruction instruction) {
    if (!(instruction instanceof InvokeInstruction call)
        || !call.owner().asInternalName().equals(INTRINSICS)) {
      return false;
    }
    var name = call.name().stringValue();
    return !name.equals("checkNotNull") && (name.startsWith("check") || name.startsWith("throw"));
  }

  /// `Intrinsics.checkNotNull(value)`, the one `!!` compiles to.
  private static boolean isBang(InvokeInstruction call) {
    return call.owner().asInternalName().equals(INTRINSICS)
        && call.name().equalsString("checkNotNull")
        && call.typeSymbol().parameterCount() == 1;
  }

  private static boolean isUninitialized(InvokeInstruction call) {
    return call.owner().asInternalName().equals(INTRINSICS)
        && call.name().equalsString(UNINITIALIZED);
  }

  /// A step that throws a `NullPointerException` on a null on top of the stack, before anything
  /// else can run: a field's read, an array's length, a call with no arguments to work out.
  private static boolean reads(Instruction next) {
    return switch (next.opcode()) {
      case GETFIELD, ARRAYLENGTH -> true;
      case INVOKEVIRTUAL, INVOKEINTERFACE ->
          next instanceof InvokeInstruction call && call.typeSymbol().parameterCount() == 0;
      default -> false;
    };
  }

  /// `ifnonnull` over `pop; ldc name; throwUninitialized…`, or `ifnull` to it.
  private static boolean guardsLateinit(List<CodeElement> code, int at, BranchInstruction branch) {
    return switch (branch.opcode()) {
      case IFNONNULL -> throwsUninitializedFrom(code, at + 1);
      case IFNULL -> throwsUninitializedFrom(code, positionOf(code, branch.target()));
      default -> false;
    };
  }

  private static int positionOf(List<CodeElement> code, Label label) {
    for (int at = 0; at < code.size(); at++) {
      if (code.get(at) instanceof LabelTarget target && target.label().equals(label)) {
        return at;
      }
    }
    return code.size();
  }

  /// `pop; ldc; throwUninitialized…` from `code[from]`.
  private static boolean throwsUninitializedFrom(List<CodeElement> code, int from) {
    var next = new java.util.ArrayList<Instruction>();
    for (int at = from; at < code.size() && next.size() < 3; at++) {
      if (code.get(at) instanceof Instruction instruction) {
        next.add(instruction);
      }
    }
    return next.size() == 3
        && next.get(0).opcode() == Opcode.POP
        && next.get(1) instanceof ConstantInstruction
        && next.get(2) instanceof InvokeInstruction call
        && isUninitialized(call);
  }

  private static Optional<Instruction> nextInstruction(List<CodeElement> code, int at) {
    return SiteSkips.instructionsAfter(code, at, 1).stream().findFirst();
  }

  /// A `lateinit` property's getter after its read of the field.
  private static final List<Opcode> LATEINIT_GETTER =
      List.of(
          Opcode.DUP,
          Opcode.IFNULL,
          Opcode.ARETURN,
          Opcode.POP,
          Opcode.LDC,
          Opcode.INVOKESTATIC,
          Opcode.ACONST_NULL,
          Opcode.ARETURN);

  /// A `lateinit` property's getter, all of it, as kotlinc writes one: read the field, give it back
  /// if it's set, or else report it. Nothing else has this shape, as the source can't give a
  /// `lateinit` a getter of its own.
  static boolean isLateinitGetter(MethodModel method) {
    var opcodes =
        method.code().stream()
            .flatMap(code -> code.elementStream())
            .filter(Instruction.class::isInstance)
            .map(element -> ((Instruction) element).opcode())
            .toList();
    int read =
        opcodes.indexOf(Opcode.GETFIELD) >= 0
            ? opcodes.indexOf(Opcode.GETFIELD)
            : opcodes.indexOf(Opcode.GETSTATIC);
    return read >= 0
        && read <= 1
        && opcodes.subList(read + 1, opcodes.size()).equals(LATEINIT_GETTER)
        && callsUninitialized(method);
  }

  private static boolean callsUninitialized(MethodModel method) {
    return method.code().stream()
        .flatMap(code -> code.elementStream())
        .anyMatch(element -> element instanceof InvokeInstruction call && isUninitialized(call));
  }
}
