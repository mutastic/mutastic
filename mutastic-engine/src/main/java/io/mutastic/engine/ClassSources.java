package io.mutastic.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/// Where each class's source is, under a source root, as its class file says: its package's
/// directory and the file its `SourceFile` attribute names. Kotlin names a file's class for the
/// file as best a class name can (`Tally.common.kt` is `Tally_commonKt`) and keeps a file's
/// composable lambdas in a class of their own, so only the attribute says which file it is.
public final class ClassSources implements Function<String, Optional<String>> {
  private final List<Path> classDirectories;
  private final Map<String, Optional<String>> found = new ConcurrentHashMap<>();

  /// The classes in `classDirectories`, the project's own.
  public ClassSources(List<Path> classDirectories) {
    this.classDirectories = List.copyOf(classDirectories);
  }

  /// `com/example/tally/Tally.common.kt` for `com/example/tally/Tally_commonKt`; none for a class
  /// that isn't there or doesn't name its file.
  @Override
  public Optional<String> apply(String internalName) {
    return found.computeIfAbsent(internalName, this::read);
  }

  private Optional<String> read(String internalName) {
    var directory =
        internalName.contains("/")
            ? internalName.substring(0, internalName.lastIndexOf('/') + 1)
            : "";
    for (var classes : classDirectories) {
      var file = classes.resolve(internalName + ".class");
      if (Files.isRegularFile(file)) {
        try {
          return ClassFile.of()
              .parse(Files.readAllBytes(file))
              .findAttribute(Attributes.sourceFile())
              .map(attribute -> directory + attribute.sourceFile().stringValue());
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
    return Optional.empty();
  }
}
