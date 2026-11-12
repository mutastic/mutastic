package io.mutastic.gradle.plugin;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectCollection;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.SourceDirectorySet;

/// A Kotlin Multiplatform module's JVM targets (`jvm()`, `jvm("desktop")`), read by name as the
/// `mutastic` command's init script reads them: the plugin compiles against Gradle alone, and can't
/// see the Kotlin plugin's types. A target's code is in its compilations, not in source sets, and
/// the `java` plugin is never applied.
final class KotlinTargets {
  static final String PLUGIN = "org.jetbrains.kotlin.multiplatform";

  private KotlinTargets() {}

  /// The module's JVM targets: none without the plugin.
  static List<Object> jvm(Project project) {
    if (!project.getPlugins().hasPlugin(PLUGIN)) {
      return List.of();
    }
    var targets = (Iterable<?>) property(project.getExtensions().getByName("kotlin"), "targets");
    return StreamSupport.stream(targets.spliterator(), false)
        .<Object>map(target -> target)
        .filter(target -> ((Enum<?>) property(target, "platformType")).name().equals("jvm"))
        .toList();
  }

  /// `desktop` for `jvm("desktop")`: its Test task is `desktopTest`, its jar `desktopJar`.
  static String name(Object target) {
    return (String) property(target, "name");
  }

  /// The target's `main` or `test` compilation.
  static Object compilation(Object target, String name) {
    return ((NamedDomainObjectCollection<?>) property(target, "compilations")).getByName(name);
  }

  static FileCollection classesDirs(Object compilation) {
    return (FileCollection) property(property(compilation, "output"), "classesDirs");
  }

  /// The compilation's resource directory, when it has one.
  static Set<File> resources(Object compilation) {
    var resources = (File) property(property(compilation, "output"), "resourcesDir");
    return resources == null ? Set.of() : Set.of(resources);
  }

  /// Every source directory of the compilation's source sets, the common ones included.
  static Set<File> sources(Object compilation) {
    var directories = new LinkedHashSet<File>();
    for (Object set : (Iterable<?>) property(compilation, "allKotlinSourceSets")) {
      directories.addAll(((SourceDirectorySet) property(set, "kotlin")).getSrcDirs());
    }
    return directories;
  }

  /// The task that builds the compilation's classes and resources.
  static String built(Object compilation) {
    return (String) property(compilation, "compileAllTaskName");
  }

  /// The configuration the compilation runs with: the tests' runtime classpath.
  static String runtime(Object compilation) {
    return (String) property(compilation, "runtimeDependencyConfigurationName");
  }

  /// `owner.name`, through its getter: `getName()`, or `isName()` for a `boolean`.
  private static Object property(Object owner, String name) {
    var capitalised = Character.toUpperCase(name.charAt(0)) + name.substring(1);
    for (var getter : List.of("get" + capitalised, "is" + capitalised)) {
      try {
        var method = owner.getClass().getMethod(getter);
        // The plugin's own classes needn't be public, though the getter is.
        method.trySetAccessible();
        return method.invoke(owner);
      } catch (NoSuchMethodException e) {
        // the other spelling
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new GradleException("mutastic: couldn't read %s of %s".formatted(name, owner), e);
      }
    }
    throw new GradleException(
        "mutastic: the Kotlin plugin's %s has no %s".formatted(owner.getClass().getName(), name));
  }
}
