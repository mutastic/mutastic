package io.mutastic.engine;

import java.util.List;

/// The production classes the coverage pass loaded, in order, which every later worker loads and
/// initialises before its first mutant.
///
/// A mutant that runs in a class's static initialiser, or in what that initialiser calls, would
/// otherwise break the class for as long as the worker lives: the first mutant of a helper that
/// reaches a new worker would fail every test that came after it, with a catch that wasn't one. The
/// coverage pass's worker is safe from that, as its classes were all initialised with no mutant in;
/// a later worker has to be made so.
///
/// That can't be done if one of those classes needs a test to have run first. Then workers start
/// empty, as they would with nothing to warm, and a worker that has aged is kept.
final class ClassesToWarm {
  private volatile List<String> classes = List.of();
  private volatile boolean refused;

  void coverageLoaded(List<String> loaded) {
    classes = refused ? List.of() : List.copyOf(loaded);
  }

  List<String> classes() {
    return classes;
  }

  /// A worker couldn't initialise them.
  void refuse() {
    refused = true;
    classes = List.of();
  }

  /// Whether a new worker is as good as the one it replaces.
  boolean canReplaceAWorker() {
    return !refused && !classes.isEmpty();
  }
}
