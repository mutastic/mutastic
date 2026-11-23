package io.mutastic.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/// Whether a framework finds a class by scanning the class files, as Spring's component scan does:
/// a test `@Configuration` in a package production code also uses, whose scan of that package finds
/// the configuration itself. Such a class must be the one the tests name, defined once by its own
/// name, not a renamed copy each run ([Renamer]): with a copy and the class the scan found, Spring
/// sees two configurations, and refuses each bean they both declare.
///
/// A class is found if an annotation on it is one of the markers, or is annotated with one, however
/// deep: `@SpringBootConfiguration` is a `@Configuration`, which is a `@Component`.
final class Scannable implements Predicate<String> {
  /// What Spring's component scan looks for, by default: `@Component`, and the standard annotations
  /// for a named bean.
  static final Set<String> SPRING =
      Set.of(
          "org/springframework/stereotype/Component",
          "jakarta/inject/Named",
          "javax/inject/Named",
          "jakarta/annotation/ManagedBean",
          "javax/annotation/ManagedBean");

  private final Set<String> markers;
  private final List<Path> roots;
  private final ClassLoader libraries;

  /// Whether each annotation type seen so far marks a class as found.
  private final Map<String, Boolean> marking = new HashMap<>();

  /// Classes and annotations come from `roots`, then from `libraries` (the project's dependencies).
  Scannable(Set<String> markers, List<Path> roots, ClassLoader libraries) {
    this.markers = Set.copyOf(markers);
    this.roots = List.copyOf(roots);
    this.libraries = libraries;
  }

  /// Whether a scan finds the class `name` (`com.example.TestApp`).
  @Override
  public boolean test(String name) {
    return annotatedWithAMarker(name.replace('.', '/'));
  }

  /// Whether an annotation on the class or annotation `internalName` marks it.
  private boolean annotatedWithAMarker(String internalName) {
    var classFile = classFile(internalName);
    return classFile != null && ClassAnnotations.of(classFile).stream().anyMatch(this::marks);
  }

  /// Whether the annotation `type` (an internal name) is a marker, or is annotated with one.
  private boolean marks(String type) {
    var known = marking.get(type);
    if (known != null) {
      return known;
    }
    marking.put(type, false); // annotations can annotate each other: `@Documented` does itself
    boolean marks =
        markers.contains(type) || (!type.startsWith("java/") && annotatedWithAMarker(type));
    marking.put(type, marks);
    return marks;
  }

  private byte @Nullable [] classFile(String internalName) {
    var own = RootsLoader.classFile(roots, internalName.replace('/', '.'));
    if (own != null) {
      return own;
    }
    try (InputStream in = libraries.getResourceAsStream(internalName + ".class")) {
      return in == null ? null : in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
