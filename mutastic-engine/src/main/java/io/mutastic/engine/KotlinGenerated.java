package io.mutastic.engine;

import io.mutastic.api.Filter;
import io.mutastic.api.Skip;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.LineNumber;
import java.util.Optional;

/// What kotlinc writes that isn't in the source. A data class's `copy`, `componentN` and `equals`
/// have no line in the source, so no test is meant to pin them: `@kotlin.Metadata` says kotlinc
/// compiled the class, and no line number in the code says it wrote the method. Likewise a
/// `lateinit` property's getter, as [KotlinChecks] tells it: the line it has is the property's. The
/// checks it writes into methods are for [KotlinChecks].
final class KotlinGenerated implements Filter {
  private static final String METADATA = "Lkotlin/Metadata;";

  @Override
  public String id() {
    return "mutastic:kotlin-generated";
  }

  @Override
  public Optional<Skip> skip(ClassModel owner, MethodModel method) {
    if (!isKotlin(owner)) {
      return Optional.empty();
    }
    if (hasCodeWithNoLines(method)) {
      return Optional.of(
          new Skip(Skip.Kind.GENERATED, "kotlinc wrote it: it has no line in the source"));
    }
    return KotlinChecks.isLateinitGetter(method)
        ? Optional.of(
            new Skip(Skip.Kind.GENERATED, "kotlinc wrote it: a `lateinit` property's getter"))
        : Optional.empty();
  }

  /// Whether kotlinc compiled the class, as its `@kotlin.Metadata` says.
  static boolean isKotlin(ClassModel owner) {
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
