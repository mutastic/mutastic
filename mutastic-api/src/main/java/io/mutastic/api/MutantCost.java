package io.mutastic.api;

import java.time.Duration;

/// What a mutant should take to run: the time its covering tests took in the coverage pass. Past
/// `slow` it's slow: three times that, or half its timeout, whichever comes first.
///
/// @param expected what its covering tests took in the coverage pass; zero for one no test reaches
/// @param slow how long it runs before progress calls it slow
/// @param tests how many tests cover it
@Internal
public record MutantCost(Duration expected, Duration slow, int tests) {
  /// A mutant no test reaches, or that isn't run: it costs nothing.
  public static final MutantCost NONE = new MutantCost(Duration.ZERO, Duration.ZERO, 0);
}
