package io.mutastic.engine;

import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/// Annotations by simple name, in any package, as a team declares its own: `@DoMutate` is any
/// annotation called `DoMutate`. Read from the bytecode, so CLASS retention is enough.
final class Annotations {
  private Annotations() {}

  /// An annotation found: its simple name, and its `value()` or `reason()` text if it has one.
  record Found(String simpleName, Optional<String> reason) {}

  /// Whether the method, or the class it's in, has an annotation called `simpleName`.
  static boolean has(ClassModel owner, MethodModel method, String simpleName) {
    return find(owner, method, Set.of(simpleName)).isPresent();
  }

  /// The first annotation on the method, then on its class, with one of the names.
  static Optional<Found> find(ClassModel owner, MethodModel method, Set<String> simpleNames) {
    return Stream.concat(on(method), on(owner))
        .map(Annotations::found)
        .filter(found -> simpleNames.contains(found.simpleName()))
        .findFirst();
  }

  /// The first annotation on the class with one of the names.
  static Optional<Found> find(ClassModel owner, Set<String> simpleNames) {
    return on(owner)
        .map(Annotations::found)
        .filter(found -> simpleNames.contains(found.simpleName()))
        .findFirst();
  }

  private static Stream<Annotation> on(AttributedElement element) {
    return Stream.concat(
        element.findAttribute(Attributes.runtimeVisibleAnnotations()).stream()
            .flatMap(attribute -> attribute.annotations().stream()),
        element.findAttribute(Attributes.runtimeInvisibleAnnotations()).stream()
            .flatMap(attribute -> attribute.annotations().stream()));
  }

  /// `Lcom/acme/Kept$DoMutate;` is called `DoMutate`.
  private static Found found(Annotation annotation) {
    var descriptor = annotation.className().stringValue();
    var binary = descriptor.substring(1, descriptor.length() - 1);
    var simple = binary.substring(Math.max(binary.lastIndexOf('/'), binary.lastIndexOf('$')) + 1);
    var reason =
        annotation.elements().stream()
            .filter(
                element ->
                    element.name().equalsString("value") || element.name().equalsString("reason"))
            .map(
                element ->
                    element.value() instanceof AnnotationValue.OfString text
                        ? text.stringValue()
                        : "")
            .filter(text -> !text.isBlank())
            .findFirst();
    return new Found(simple, reason);
  }
}
