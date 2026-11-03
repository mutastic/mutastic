package io.mutastic.worker;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.core.LauncherFactory;

/// For tests that run in a framework which loads the app in a class loader of its own
/// (`@QuarkusTest`): a class from the worker's loader is refused there, and a mutant in it never
/// reaches the framework's copy. So the framework loads the project's classes itself, from the
/// classpath, and the agent watches it: a class defined while mutastic has bytes for it (an
/// instrumented copy, a mutant) gets those bytes, and one already loaded is redefined in place.
/// Mutants change method bodies only, which redefinition allows, so the app stays up between them.
final class Framework {
  private final List<Path> productionRoots;
  private final Set<String> projectClasses;

  /// Bytes to define a project class with instead of the build's: instrumented, or a mutant.
  private final Map<String, byte[]> replacements = new ConcurrentHashMap<>();

  /// Each project class's bytes as a loader first defined it, before any replacement.
  private final Map<String, byte[]> defined = new ConcurrentHashMap<>();

  /// The classes the framework defined with bytes of its own, not the build's: it rewrote them as
  /// it loaded them, so bytes made from the build's would undo that.
  private final Set<String> rewritten = ConcurrentHashMap.newKeySet();

  /// One session for the worker's life: Quarkus stops its app when a session closes, and would
  /// start it again for every mutant.
  private @Nullable LauncherSession session;

  Framework(List<Path> productionRoots, Set<String> projectClasses) {
    this.productionRoots = List.copyOf(productionRoots);
    this.projectClasses = Set.copyOf(projectClasses);
    Agent.watch(this::defining);
  }

  /// A loader is defining `name`: keeps its bytes, and answers with a replacement when there is one
  /// and the bytes are the build's. The worker's own loaders are left alone.
  private byte @Nullable [] defining(ClassLoader loader, String name, byte[] bytes) {
    if (loader instanceof RootsLoader || !projectClasses.contains(name)) {
      return null;
    }
    defined.putIfAbsent(name, bytes);
    if (!Arrays.equals(bytes, buildBytes(name))) {
      rewritten.add(name);
      return null;
    }
    return replacements.get(name);
  }

  /// Puts these classes in: redefined wherever they're loaded, and the bytes to define them with
  /// where they aren't yet.
  ///
  /// @throws IllegalStateException when the framework rewrote one as it loaded it
  void swapIn(Map<String, byte[]> classes) throws Exception {
    for (var each : classes.entrySet()) {
      if (rewritten.contains(each.getKey())) {
        throw new IllegalStateException(
            "the framework rewrote "
                + each.getKey()
                + " as it loaded it, so mutants can't reach it");
      }
      replacements.put(each.getKey(), each.getValue());
      Agent.redefineEverywhere(each.getKey(), each.getValue(), Framework::notOurs);
    }
  }

  /// Back to the bytes each loader defined.
  void swapBack(Set<String> names) throws Exception {
    for (String name : names) {
      replacements.remove(name);
      var original = defined.get(name);
      if (original != null) {
        Agent.redefineEverywhere(name, original, Framework::notOurs);
      }
    }
  }

  /// The launcher every run uses, in the worker's one session.
  Launcher launcher() {
    if (session == null) {
      session = LauncherFactory.openSession();
    }
    return session.getLauncher();
  }

  private byte @Nullable [] buildBytes(String name) {
    return RootsLoader.classFile(productionRoots, name);
  }

  private static boolean notOurs(@Nullable ClassLoader loader) {
    return !(loader instanceof RootsLoader);
  }
}
