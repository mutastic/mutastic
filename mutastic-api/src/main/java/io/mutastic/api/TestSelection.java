package io.mutastic.api;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

/// Which of a module's tests its build runs: the test task's or Surefire's patterns and JUnit tags.
/// mutastic runs the same tests, so a test the build leaves out (an integration test, a slow tag)
/// neither catches nor misses a mutant.
///
/// @param includes class-file patterns, relative to a test class directory: globs as Ant writes
///   them (`**/*Test.class`, where `**/` also matches no directory), or `regex:` and a regular
///   expression; empty includes every class
/// @param excludes class-file patterns to leave out, in the same form
/// @param includeTags JUnit tags (or tag expressions) a test must have; empty means any
/// @param excludeTags JUnit tags (or tag expressions) a test must not have
public record TestSelection(
    List<String> includes,
    List<String> excludes,
    List<String> includeTags,
    List<String> excludeTags) {
  /// Every test, as when a build says nothing.
  public static final TestSelection ALL =
      new TestSelection(List.of(), List.of(), List.of(), List.of());

  public TestSelection {
    includes = List.copyOf(includes);
    excludes = List.copyOf(excludes);
    includeTags = List.copyOf(includeTags);
    excludeTags = List.copyOf(excludeTags);
  }

  /// The same, leaving out these test classes as well: each by its name (`RacyTest`, in any
  /// package) or its full name (`com.example.RacyTest`).
  public TestSelection leavingOut(List<String> classes) {
    var more = new java.util.ArrayList<>(excludes);
    for (String name : classes) {
      more.add(name.contains(".") ? name.replace('.', '/') + ".class" : "**/" + name + ".class");
    }
    return new TestSelection(includes, more, includeTags, excludeTags);
  }

  /// Whether the build runs the top-level test class `className` (`com.example.PricingTest`).
  public boolean runs(String className) {
    var file = Path.of(className.replace('.', '/') + ".class");
    return (includes.isEmpty() || includes.stream().anyMatch(pattern -> matches(pattern, file)))
        && excludes.stream().noneMatch(pattern -> matches(pattern, file));
  }

  private static boolean matches(String pattern, Path file) {
    if (pattern.startsWith("regex:")) {
      return matcher(pattern).matches(file);
    }
    var glob =
        pattern.startsWith("**/") ? List.of(pattern, pattern.substring(3)) : List.of(pattern);
    return glob.stream().anyMatch(each -> matcher("glob:" + each).matches(file));
  }

  private static PathMatcher matcher(String syntaxAndPattern) {
    return FileSystems.getDefault().getPathMatcher(syntaxAndPattern);
  }
}
