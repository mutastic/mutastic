package io.mutastic.engine;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// How the source names a class, for a call a suggested test writes. A binary name's `$` usually
/// divides a nested class from its outer one, but a name may hold a `$` of its own: `$Cache` is a
/// top-level class, and so is a framework's `$Cache$Definition`. The class file's `InnerClasses`
/// attribute says which classes are nested, so a call is offered only when the name it writes is
/// the one the source declares.
final class JavaNames {
  private JavaNames() {}

  /// `Shipping.Parcel` for `Shipping$Parcel`, and `$Cache` for itself: a `$` read as dividing
  /// two names only between two other characters, as a name may start or end with one.
  static String nested(String simpleBinaryName) {
    var written = new StringBuilder(simpleBinaryName);
    for (int at = 1; at < written.length() - 1; at++) {
      if (written.charAt(at) == '$'
          && written.charAt(at - 1) != '$'
          && written.charAt(at + 1) != '$') {
        written.setCharAt(at, '.');
      }
    }
    return written.toString();
  }

  /// `Shipping.Parcel` for `com/example/Shipping$Parcel`, as [#nested] writes it.
  static String nestedOf(String internalName) {
    return nested(internalName.substring(internalName.lastIndexOf('/') + 1));
  }

  /// Whether [#nestedOf] writes each class as its source declares it: a class not among `models`
  /// is taken as written, as nothing says otherwise.
  static boolean writtenAsDeclared(List<String> internalNames, Map<String, ClassModel> models) {
    return internalNames.stream()
        .allMatch(
            name -> {
              var model = models.get(name.replace('/', '.'));
              return model == null || declared(model).equals(Optional.of(nestedOf(name)));
            });
  }

  /// The name the source declares the class by, outer classes first (`Shipping.Parcel`), from its
  /// `InnerClasses` attribute, which lists the class and every class it's nested in; none for a
  /// local or anonymous class, which no other code can name.
  static Optional<String> declared(ClassModel model) {
    var entries =
        model
            .findAttribute(Attributes.innerClasses())
            .map(attribute -> attribute.classes())
            .orElse(List.of());
    var current = model.thisClass().asInternalName();
    var name = new StringBuilder();
    for (int depth = 0; depth <= entries.size(); depth++) {
      var entry = entryFor(entries, current);
      if (entry.isEmpty()) {
        return Optional.of(prefixed(current.substring(current.lastIndexOf('/') + 1), name));
      }
      var outer = entry.get().outerClass();
      var simple = entry.get().innerName().map(Utf8Entry::stringValue);
      if (outer.isEmpty() || simple.isEmpty()) {
        return Optional.empty();
      }
      name.insert(0, name.isEmpty() ? simple.get() : simple.get() + ".");
      current = outer.get().asInternalName();
    }
    return Optional.empty(); // a cycle, which javac never writes
  }

  private static Optional<InnerClassInfo> entryFor(List<InnerClassInfo> entries, String name) {
    return entries.stream()
        .filter(entry -> entry.innerClass().asInternalName().equals(name))
        .findFirst();
  }

  private static String prefixed(String topLevel, StringBuilder nested) {
    return nested.isEmpty() ? topLevel : topLevel + "." + nested;
  }
}
