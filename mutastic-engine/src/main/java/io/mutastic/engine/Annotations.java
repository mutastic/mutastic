package io.mutastic.engine;

import java.lang.classfile.Annotation;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.util.stream.Stream;

/// Annotations by simple name, in any package, as a team declares its own: `@DoMutate` is any
/// annotation called `DoMutate`. Read from the bytecode, so CLASS retention is enough.
final class Annotations {
  private Annotations() {}

  /// Whether the method, or the class it's in, has an annotation called `simpleName`.
  static boolean has(ClassModel owner, MethodModel method, String simpleName) {
    return Stream.concat(on(owner), on(method))
        .anyMatch(annotation -> named(annotation, simpleName));
  }

  private static Stream<Annotation> on(AttributedElement element) {
    return Stream.concat(
        element.findAttribute(Attributes.runtimeVisibleAnnotations()).stream()
            .flatMap(attribute -> attribute.annotations().stream()),
        element.findAttribute(Attributes.runtimeInvisibleAnnotations()).stream()
            .flatMap(attribute -> attribute.annotations().stream()));
  }

  /// `Lcom/acme/Kept$DoMutate;` is called `DoMutate`.
  private static boolean named(Annotation annotation, String simpleName) {
    var descriptor = annotation.className().stringValue();
    var binary = descriptor.substring(1, descriptor.length() - 1);
    var simple = binary.substring(Math.max(binary.lastIndexOf('/'), binary.lastIndexOf('$')) + 1);
    return simple.equals(simpleName);
  }
}
