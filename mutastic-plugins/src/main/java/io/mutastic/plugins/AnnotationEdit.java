package io.mutastic.plugins;

import java.lang.classfile.Annotation;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.util.List;
import java.util.function.Predicate;

/// One annotation type taken out of what a class, field, method or record component says at run
/// time. A declaration annotation that can also go on a type (`@NotNull` can) is written twice,
/// once on the declaration and once on its type: both go, or reflection still finds it. A type
/// nested in another (`List<@NotNull String>`) is another annotation and stays.
final class AnnotationEdit {
  private final String descriptor;

  AnnotationEdit(String descriptor) {
    this.descriptor = descriptor;
  }

  /// The attribute without it, or `null` when that leaves it empty, and it should go.
  java.lang.classfile.@org.jspecify.annotations.Nullable Attribute<?> without(
      RuntimeVisibleAnnotationsAttribute attribute) {
    var kept = kept(attribute.annotations());
    return kept.isEmpty() ? null : RuntimeVisibleAnnotationsAttribute.of(kept);
  }

  /// The attribute without it on the parameter `index`. It keeps every parameter's place.
  RuntimeVisibleParameterAnnotationsAttribute withoutOnParameter(
      RuntimeVisibleParameterAnnotationsAttribute attribute, int index) {
    var all = new java.util.ArrayList<>(attribute.parameterAnnotations());
    all.set(index, kept(all.get(index)));
    return RuntimeVisibleParameterAnnotationsAttribute.of(all);
  }

  /// The attribute without its copies that `target` picks, or `null` when that leaves it empty.
  java.lang.classfile.@org.jspecify.annotations.Nullable Attribute<?> withoutOnType(
      RuntimeVisibleTypeAnnotationsAttribute attribute,
      Predicate<TypeAnnotation.TargetInfo> target) {
    var kept =
        attribute.annotations().stream()
            .filter(each -> !(isCopy(each) && target.test(each.targetInfo())))
            .toList();
    return kept.isEmpty() ? null : RuntimeVisibleTypeAnnotationsAttribute.of(kept);
  }

  /// Whether the type annotation is this annotation on the declared type itself, or for an array on
  /// its elements, as `@NotNull String[] names` is written.
  private boolean isCopy(TypeAnnotation annotation) {
    return annotation.annotation().className().equalsString(descriptor)
        && annotation.targetPath().stream()
            .allMatch(part -> part.typePathKind() == TypeAnnotation.TypePathComponent.Kind.ARRAY);
  }

  private List<Annotation> kept(List<Annotation> annotations) {
    return annotations.stream()
        .filter(annotation -> !annotation.className().equalsString(descriptor))
        .toList();
  }

  /// The run-time annotations directly on `element`.
  static List<Annotation> on(AttributedElement element) {
    return element
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .map(RuntimeVisibleAnnotationsAttribute::annotations)
        .orElse(List.of());
  }
}
