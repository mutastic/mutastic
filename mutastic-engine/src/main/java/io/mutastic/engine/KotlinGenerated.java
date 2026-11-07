package io.mutastic.engine;

import io.mutastic.api.Filter;
import io.mutastic.api.Skip;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.util.Optional;

/// What kotlinc writes that isn't in the source. A data class's `copy`, `componentN` and `equals`
/// have no line in the source, so no test is meant to pin them: `@kotlin.Metadata` says kotlinc
/// compiled the class, and no line number in the code says it wrote the method. And the checks it
/// writes into methods, `Intrinsics.checkNotNullParameter` and the like, which fail only if a
/// caller outside Kotlin breaks a promise the types already make.
final class KotlinGenerated implements Filter {
  private static final String METADATA = "Lkotlin/Metadata;";
  private static final String INTRINSICS = "kotlin/jvm/internal/Intrinsics";

  @Override
  public String id() {
    return "mutastic:kotlin-generated";
  }

  @Override
  public Optional<Skip> skip(ClassModel owner, MethodModel method) {
    return isKotlin(owner) && hasCodeWithNoLines(method)
        ? Optional.of(
            new Skip(Skip.Kind.GENERATED, "kotlinc wrote it: it has no line in the source"))
        : Optional.empty();
  }

  /// Whether the call is one of the checks kotlinc writes in: not `Intrinsics.areEqual`, which is
  /// what `==` in the source compiles to.
  static boolean isInserted(InvokeInstruction call) {
    var name = call.name().stringValue();
    return call.owner().asInternalName().equals(INTRINSICS)
        && (name.startsWith("check") || name.startsWith("throw"));
  }

  private static boolean isKotlin(ClassModel owner) {
    return owner
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .map(
            attribute ->
                attribute.annotations().stream()
                    .anyMatch(annotation -> annotation.className().equalsString(METADATA)))
        .orElse(false);
  }

  private static boolean hasCodeWithNoLines(MethodModel method) {
    return method
        .code()
        .map(code -> code.elementStream().noneMatch(LineNumber.class::isInstance))
        .orElse(false);
  }
}
