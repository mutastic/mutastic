package io.mutastic.engine;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
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

  /// `com/example/tally/Tally.common.kt` for `com/example/tally/Tally_commonKt`; for a class that
  /// doesn't name its file, the file of the top-level class it's in, as its `InnerClasses` and
  /// `EnclosingMethod` attributes say, named for that class: `com/example/$Cache.java`, never one
  /// guessed from where a `$` falls in the name. None for a class that isn't there.
  @Override
  public Optional<String> apply(String internalName) {
    return found.computeIfAbsent(internalName, this::read);
  }

  private Optional<String> read(String internalName) {
    var seen = new java.util.HashSet<String>();
    var current = internalName;
    // A class nested in itself is no class javac writes: the walk stops at the first one seen.
    while (seen.add(current)) {
      var model = model(current);
      if (model.isEmpty()) {
        return current.equals(internalName) ? Optional.empty() : Optional.of(current + ".java");
      }
      var named = model.get().findAttribute(Attributes.sourceFile());
      if (named.isPresent()) {
        return Optional.of(directoryOf(current) + named.get().sourceFile().stringValue());
      }
      var outer = enclosing(model.get());
      if (outer.isEmpty()) {
        break;
      }
      current = outer.get();
    }
    return Optional.of(current + ".java");
  }

  /// The class `model` is declared in, if it's nested: a member class's outer class, or a local
  /// or anonymous class's enclosing one.
  private static Optional<String> enclosing(ClassModel model) {
    var self = model.thisClass().asInternalName();
    var outer =
        model
            .findAttribute(Attributes.innerClasses())
            .flatMap(
                attribute ->
                    attribute.classes().stream()
                        .filter(inner -> inner.innerClass().asInternalName().equals(self))
                        .findFirst()
                        .flatMap(inner -> inner.outerClass())
                        .map(ClassEntry::asInternalName));
    return outer.or(
        () ->
            model
                .findAttribute(Attributes.enclosingMethod())
                .map(attribute -> attribute.enclosingClass().asInternalName()));
  }

  private Optional<ClassModel> model(String internalName) {
    for (var classes : classDirectories) {
      var file = classes.resolve(internalName + ".class");
      if (Files.isRegularFile(file)) {
        return Optional.of(ClassFileVersion.parse(ClassFile.of(), file));
      }
    }
    return Optional.empty();
  }

  /// `com/example/` for `com/example/Pricing`.
  private static String directoryOf(String internalName) {
    return internalName.substring(0, internalName.lastIndexOf('/') + 1);
  }
}
