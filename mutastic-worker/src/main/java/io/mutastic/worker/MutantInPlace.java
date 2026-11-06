package io.mutastic.worker;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/// Which mutant the production classes hold between one mutant's run and the next.
///
/// Redefining a class takes the JVM tens of milliseconds whichever class it is, and that's more
/// than most tests that catch a mutant take. Mutants come in source order, so the next one is
/// usually of the same class, and redefining that class back to the build's bytes just to redefine
/// it again is half the cost for nothing. So a mutant stays in place until something else is
/// wanted: the next mutant of its class goes straight in its place, and anything else restores the
/// class first. No test runs in between, so none can see the difference.
final class MutantInPlace {
  /// What classes can be swapped in and out of.
  interface Classes {
    void swapIn(Map<String, byte[]> mutants) throws Exception;

    void swapBack(Set<String> names) throws Exception;
  }

  private final Set<String> inPlace = new HashSet<>();

  /// Puts this mutant in, after restoring any class of the last one that it doesn't replace. With
  /// no classes, as for a run of the code as built, that's every class.
  void put(Classes classes, Map<String, byte[]> mutant) throws Exception {
    var others = new HashSet<>(inPlace);
    others.removeAll(mutant.keySet());
    classes.swapBack(others);
    inPlace.clear();
    classes.swapIn(mutant);
    inPlace.addAll(mutant.keySet());
  }

  /// Restores the build's bytes for whatever the last mutant changed.
  void restore(Classes classes) throws Exception {
    classes.swapBack(Set.copyOf(inPlace));
    inPlace.clear();
  }
}
