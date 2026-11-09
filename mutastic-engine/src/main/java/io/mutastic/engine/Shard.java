package io.mutastic.engine;

/// One of a run's shares of the mutants, for splitting one run across machines: the `number`th of
/// `of`, dealt by each mutant's id alone, so a mutant is in exactly one share and the shares'
/// results together are the whole run's.
///
/// @param number which share, from 1
/// @param of how many shares the mutants are dealt into
public record Shard(int number, int of) {
  /// Every mutant: a run that isn't split.
  public static final Shard WHOLE = new Shard(1, 1);

  /// The most shares a run is split into: more machines than this each start a build for little.
  public static final int MOST = 64;

  public Shard {
    if (of < 1 || of > MOST || number < 1 || number > of) {
      throw new IllegalArgumentException("no share " + number + " of " + of);
    }
  }

  /// Whether the mutant with this id is in this share, by its id's hash.
  public boolean holds(String id) {
    return Math.floorMod(id.hashCode(), of) == number - 1;
  }

  /// Whether this is every mutant.
  public boolean whole() {
    return of == 1;
  }
}
