package io.mutastic.worker;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/// Swaps mutants into classes that are already loaded. A mutant changes one method body, which
/// class redefinition allows, so the project's classes load once per worker and each mutant is
/// swapped in for its tests and swapped back after. The worker starts with
/// `-javaagent:mutastic-worker.jar`.
public final class Agent {
  /// Set by `premain`; null when the worker started without the agent.
  private static @Nullable Instrumentation instrumentation;

  private Agent() {}

  public static void premain(String arguments, Instrumentation inst) {
    instrumentation = inst;
  }

  /// What the agent asks when a loader defines a class: bytes to define it with instead, or null.
  interface Definitions {
    byte @Nullable [] defining(ClassLoader loader, String name, byte[] bytes);
  }

  /// Every class a loader defines (not redefines) goes past `definitions` first.
  static void watch(Definitions definitions) {
    instrumentation()
        .addTransformer(
            new ClassFileTransformer() {
              @Override
              public byte @Nullable [] transform(
                  @Nullable ClassLoader loader,
                  @Nullable String internalName,
                  @Nullable Class<?> redefining,
                  ProtectionDomain domain,
                  byte[] bytes) {
                if (loader == null || internalName == null || redefining != null) {
                  return null;
                }
                return definitions.defining(loader, internalName.replace('/', '.'), bytes);
              }
            });
  }

  /// Replaces every loaded copy of `name` in the loaders `where` accepts: none, if it isn't loaded
  /// yet.
  static void redefineEverywhere(String name, byte[] bytes, Predicate<ClassLoader> where)
      throws Exception {
    var definitions = new ArrayList<ClassDefinition>();
    for (Class<?> loaded : instrumentation().getAllLoadedClasses()) {
      if (loaded.getName().equals(name) && where.test(loaded.getClassLoader())) {
        definitions.add(new ClassDefinition(loaded, bytes));
      }
    }
    if (!definitions.isEmpty()) {
      instrumentation().redefineClasses(definitions.toArray(ClassDefinition[]::new));
    }
  }

  private static Instrumentation instrumentation() {
    if (instrumentation == null) {
      throw new IllegalStateException("the worker must start with -javaagent:<the worker jar>");
    }
    return instrumentation;
  }

  /// Replaces a loaded class's bytes: method bodies may differ, its shape may not.
  static void redefine(Class<?> loaded, byte[] bytes) throws Exception {
    if (instrumentation == null) {
      throw new IllegalStateException("the worker must start with -javaagent:<the worker jar>");
    }
    instrumentation.redefineClasses(new ClassDefinition(loaded, bytes));
  }
}
