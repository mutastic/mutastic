package io.mutastic.worker;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
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

  /// Replaces a loaded class's bytes: method bodies may differ, its shape may not.
  static void redefine(Class<?> loaded, byte[] bytes) throws Exception {
    if (instrumentation == null) {
      throw new IllegalStateException("the worker must start with -javaagent:<the worker jar>");
    }
    instrumentation.redefineClasses(new ClassDefinition(loaded, bytes));
  }
}
