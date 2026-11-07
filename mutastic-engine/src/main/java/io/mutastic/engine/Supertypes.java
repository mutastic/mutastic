package io.mutastic.engine;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/// What a class really extends, from class files, without loading them: a filter goes by evidence,
/// and a class's name is none. The `ClassFile` context resolves the same supertypes, but keeps what
/// it finds to itself.
final class Supertypes {
  private static final String THROWABLE = "java/lang/Throwable";

  private final Function<String, @Nullable InputStream> classFiles;

  /// @param classFiles a class file by its internal name, or `null` when there's no such file
  Supertypes(Function<String, @Nullable InputStream> classFiles) {
    this.classFiles = classFiles;
  }

  /// The JDK's classes, and what else `loader` can see.
  static Supertypes in(ClassLoader loader) {
    return new Supertypes(name -> loader.getResourceAsStream(name + ".class"));
  }

  /// Whether walking up from `name` reaches `Throwable`. A class on the way that can't be read says
  /// nothing either way, and so isn't one.
  boolean isThrowable(String name) {
    var at = Optional.of(name);
    while (at.isPresent() && !at.get().equals(THROWABLE)) {
      at = superclassOf(at.get());
    }
    return at.isPresent();
  }

  private Optional<String> superclassOf(String name) {
    try (var file = classFiles.apply(name)) {
      return file == null
          ? Optional.empty()
          : ClassFile.of().parse(file.readAllBytes()).superclass().map(ClassEntry::asInternalName);
    } catch (IOException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
