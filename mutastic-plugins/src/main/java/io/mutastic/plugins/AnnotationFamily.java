package io.mutastic.plugins;

import java.util.Set;

/// The annotations one operator removes: by exact type, and by the packages whose annotations all
/// count, as the JVM writes them. Never by simple name: a team's own `@NotNull` may mean something
/// else, or nothing at run time.
record AnnotationFamily(Set<String> types, Set<String> packages) {
  /// Whether `descriptor`, `Ljakarta/validation/constraints/NotNull;`, is one of them.
  boolean has(String descriptor) {
    return types.contains(descriptor) || packages.stream().anyMatch(descriptor::startsWith);
  }

  /// `Size.List` for `Ljakarta/validation/constraints/Size$List;`.
  static String simpleName(String descriptor) {
    var binary = descriptor.substring(1, descriptor.length() - 1);
    return binary.substring(binary.lastIndexOf('/') + 1).replace('$', '.');
  }
}
