package io.mutastic.worker;

/// Whether this worker has got slow enough to be worth replacing.
///
/// A class redefinition walks every class the JVM has loaded, so it takes longer the more there
/// are, and a worker only gains them: each run of a test in a package production code also uses
/// defines a renamed copy in the production loader ([Renamer]), which is never unloaded, and that
/// can't be helped, as nothing else gives a test its package access and clean statics. On
/// mutastic's own engine a swap went from about 25 ms to over a second within a few thousand test
/// runs, which was a third of what the workers did all night.
///
/// A new worker has its own cost: starting up, initialising the project's classes and warming up
/// again. So this waits until the time lost to swaps, against the fastest this worker has done,
/// comes to about that. A project whose swaps stay as fast as they began keeps its worker; one that
/// grows quickly replaces it often, and neither pays much more than the other choice would have
/// cost.
final class Aging {
  private final long worthANewWorker;
  private long fastest = Long.MAX_VALUE;
  private long lost;

  /// @param worthANewWorker the nanoseconds lost to slower swaps that a new worker costs
  Aging(long worthANewWorker) {
    this.worthANewWorker = worthANewWorker;
  }

  /// Hears how long a swap took, in nanoseconds; true once this worker is worth replacing.
  boolean swapTook(long nanos) {
    fastest = Math.min(fastest, nanos);
    lost += nanos - fastest;
    return lost >= worthANewWorker;
  }
}
