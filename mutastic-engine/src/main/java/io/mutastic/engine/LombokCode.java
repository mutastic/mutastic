package io.mutastic.engine;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/// What Lombok writes into a method, told by what's in the class file. A member Lombok writes as a
/// whole carries `@lombok.Generated` (from Lombok 1.18.34, or before with
/// `lombok.addLombokGeneratedAnnotation = true`), which [AnnotatedSkips] already skips; two things
/// it writes are told here:
///
/// - **The close a `@Cleanup` writes** in the person's own method, `if
///   (Collections.singletonList(r).get(0) != null) r.close();`, on the normal path and again when
///   the body throws. As with a try-with-resources's close, the person asked for the resource to be
///   closed, not for this code, and its cards would ask for a resource that is null.
/// - **The null check a `@NonNull` asks for,** `if (x == null) throw new NullPointerException("x is
///   marked non-null but is null");`. The person wrote `@NonNull` as they'd write the check, so it
///   runs, in a setter Lombok wrote as much as in their own method: without it, a setter takes
///   `null` without a word. The exception can be another (`lombok.nonNull.exceptionType`); the
///   message is Lombok's whichever it is.
final class LombokCode {
  static final String CLEANUP = "generated: closes a `@Cleanup` resource";

  private static final String MARKER = "Llombok/Generated;";

  /// What every `@NonNull` check's message ends with.
  private static final String NON_NULL = " is marked non-null but is null";

  private LombokCode() {}

  /// Where in `code` each `@Cleanup`'s close is: `aload r; Collections.singletonList; iconst_0;
  /// List.get; ifnull; aload r; close()`. By position, as an instruction with no operand, like
  /// `iconst_0`, can be the same object wherever it is.
  static Set<Integer> cleanups(List<CodeElement> code) {
    return found(code, 7, LombokCode::isCleanup);
  }

  /// Where in `code` each `@NonNull` check of a member Lombok marked as its own is, from the jump
  /// to the throw: nowhere in any other method, where nothing skips the check to begin with.
  static Set<Integer> nonNullChecks(ClassModel owner, MethodModel method, List<CodeElement> code) {
    return isLombok(owner, method) ? found(code, 6, LombokCode::isNonNullCheck) : Set.of();
  }

  /// The positions of every run of `length` instructions in `code` that `matches`.
  private static Set<Integer> found(
      List<CodeElement> code, int length, Predicate<List<Instruction>> matches) {
    var positions = new ArrayList<Integer>();
    for (int at = 0; at < code.size(); at++) {
      if (code.get(at) instanceof Instruction) {
        positions.add(at);
      }
    }
    var found = new HashSet<Integer>();
    for (int first = 0; first + length <= positions.size(); first++) {
      var run = positions.subList(first, first + length);
      if (matches.test(run.stream().map(at -> (Instruction) code.get(at)).toList())) {
        found.addAll(run);
      }
    }
    return found;
  }

  /// Whether Lombok marked the method, or the class it's in, as its own: its own annotation, not
  /// any called `Generated`.
  static boolean isLombok(ClassModel owner, MethodModel method) {
    return Stream.of(
            method.findAttribute(Attributes.runtimeInvisibleAnnotations()),
            owner.findAttribute(Attributes.runtimeInvisibleAnnotations()))
        .flatMap(java.util.Optional::stream)
        .flatMap(attribute -> attribute.annotations().stream())
        .anyMatch(annotation -> annotation.className().equalsString(MARKER));
  }

  /// `ifnonnull; new; dup; ldc "x is marked non-null but is null"; invokespecial; athrow`.
  private static boolean isNonNullCheck(List<Instruction> six) {
    return six.get(0).opcode() == Opcode.IFNONNULL
        && six.get(1).opcode() == Opcode.NEW
        && six.get(3) instanceof ConstantInstruction message
        && message.constantValue() instanceof String text
        && text.endsWith(NON_NULL)
        && six.get(5).opcode() == Opcode.ATHROW;
  }

  private static boolean isCleanup(List<Instruction> seven) {
    return seven.get(0) instanceof LoadInstruction resource
        && isCall(seven.get(1), "java/util/Collections", "singletonList")
        && seven.get(2).opcode() == Opcode.ICONST_0
        && isCall(seven.get(3), "java/util/List", "get")
        && seven.get(4).opcode() == Opcode.IFNULL
        && seven.get(5) instanceof LoadInstruction again
        && again.slot() == resource.slot()
        && seven.get(6) instanceof InvokeInstruction close
        && close.name().equalsString("close");
  }

  private static boolean isCall(Instruction instruction, String owner, String name) {
    return instruction instanceof InvokeInstruction call
        && call.owner().asInternalName().equals(owner)
        && call.name().equalsString(name);
  }
}
