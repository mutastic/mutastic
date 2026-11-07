package io.mutastic.engine;

import io.mutastic.api.Mutant;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// Where a class's source is: its package's directory in one of the source roots, and the file its
/// `SourceFile` attribute names, so a nested class is in its outer class's file.
final class SourcePaths {
  private SourcePaths() {}

  /// The source file a mutant's line is in, absolute: the file its code was copied from, or else
  /// its class's. `model` is its class, if known.
  static Optional<Path> of(Mutant mutant, Optional<ClassModel> model, List<Path> roots) {
    return mutant
        .inlinedFrom()
        .map(
            file ->
                roots.stream()
                    .map(root -> root.resolve(file).toAbsolutePath().normalize())
                    .filter(Files::isRegularFile)
                    .findFirst())
        .orElseGet(() -> model.flatMap(found -> of(found, roots)));
  }

  /// The source file, absolute, or none when the class has no source in these roots.
  static Optional<Path> of(ClassModel model, List<Path> roots) {
    var internal = model.thisClass().asInternalName();
    var directory = internal.contains("/") ? internal.substring(0, internal.lastIndexOf('/')) : "";
    return model
        .findAttribute(Attributes.sourceFile())
        .map(attribute -> attribute.sourceFile().stringValue())
        .flatMap(
            file ->
                roots.stream()
                    .map(root -> root.resolve(directory).resolve(file).toAbsolutePath().normalize())
                    .filter(Files::isRegularFile)
                    .findFirst());
  }
}
