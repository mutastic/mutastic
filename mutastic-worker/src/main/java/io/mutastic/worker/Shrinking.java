package io.mutastic.worker;

import java.lang.reflect.Method;
import org.jspecify.annotations.Nullable;

/// Property tests' shrinking, turned off for a mutant's run where the framework lets it be: the
/// first counterexample already catches the mutant, and shrinking it is often the costly part of a
/// failing property. On again for the coverage pass, and for a run that names the smallest input.
///
/// - QuickTheories reads the system property `QT_SHRINKS` for each theory: `0` shrinks nothing.
/// - Kotest's property tests read `PropertyTesting.defaultShrinkingMode` for each property: set to
///   `ShrinkingMode.Off`. Kotest 6 also reads a system property for it, but only once, as it
///   starts, and Kotest 5 none.
/// - junit-quickcheck shrinks as its `@Property(shrink=…)` says, which nothing outside the test
///   sets.
///
/// What the person set is what "on" puts back.
final class Shrinking {
  private static final String QT_SHRINKS = "QT_SHRINKS";

  private final @Nullable String theirShrinks = System.getProperty(QT_SHRINKS);

  /// Kotest's `PropertyTesting`, once looked for: empty when the tests don't use Kotest.
  private @Nullable Kotest kotest;

  private boolean lookedForKotest;

  /// Whether the last runs were set to shrink nothing.
  private boolean off;

  /// Kotest's setting, and the value it had before mutastic turned shrinking off.
  private record Kotest(Object settings, Method set, Object theirs, Object none) {}

  /// Shrinking for the next tests: as the person has it, or off where it can be turned off.
  void set(boolean shrink) {
    off = !shrink;
    if (shrink) {
      restore(theirShrinks);
    } else {
      System.setProperty(QT_SHRINKS, "0");
    }
    var found = kotest();
    if (found != null) {
      invoke(found, shrink ? found.theirs() : found.none());
    }
  }

  /// Whether shrinking is off for the tests running now.
  boolean off() {
    return off;
  }

  private static void restore(@Nullable String shrinks) {
    if (shrinks == null) {
      System.clearProperty(QT_SHRINKS);
    } else {
      System.setProperty(QT_SHRINKS, shrinks);
    }
  }

  private static void invoke(Kotest found, Object mode) {
    try {
      found.set().invoke(found.settings(), mode);
    } catch (ReflectiveOperationException | RuntimeException e) {
      // a Kotest whose settings aren't these: its properties shrink as they would
    }
  }

  private @Nullable Kotest kotest() {
    if (!lookedForKotest) {
      lookedForKotest = true;
      kotest = foundKotest();
    }
    return kotest;
  }

  /// Kotest's settings object from the classpath the tests' libraries load from, if it's there.
  private static @Nullable Kotest foundKotest() {
    var loader = ClassLoader.getSystemClassLoader();
    try {
      var settings = Class.forName("io.kotest.property.PropertyTesting", true, loader);
      var mode = Class.forName("io.kotest.property.ShrinkingMode", false, loader);
      var none = Class.forName("io.kotest.property.ShrinkingMode$Off", true, loader);
      var instance = settings.getField("INSTANCE").get(null);
      var theirs = settings.getMethod("getDefaultShrinkingMode").invoke(instance);
      var set = settings.getMethod("setDefaultShrinkingMode", mode);
      return new Kotest(instance, set, theirs, none.getField("INSTANCE").get(null));
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      return null;
    }
  }
}
