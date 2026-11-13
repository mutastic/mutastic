package io.mutastic.api;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/// The one name close to a mistyped one, for an error to say "There's one called …": a command, an
/// option, a value, a key in a file, a module or an id. Said, never acted on: the person or agent
/// runs it, so a guess must be likely.
///
/// The rule, the same in both editions (mutastic-rs's `similar::closest`): the fewest edits, where
/// an edit inserts, deletes or changes a character or swaps two next to each other, compared
/// without case, and at most `max(length, 3) / 3` of them. Of two as close, the one nearer the
/// typed length wins, then the first: a swap keeps the length, so `tset` is `test`, not `set`. Both
/// editions' tests read the same table of typos (`typos.tsv`).
@Internal
public final class Nearest {
  private Nearest() {}

  /// The name in `names` that `typed` is closest to, by the rule above.
  public static Optional<String> name(String typed, Collection<String> names) {
    return name(typed, names, Function.identity());
  }

  /// The name in `names` that `typed` is closest to, comparing what `key` makes of each: the words
  /// without their `--` or `:`, so the punctuation counts for no edits.
  public static Optional<String> name(
      String typed, Collection<String> names, Function<String, String> key) {
    return name(typed, names, key, Math.max(key.apply(typed).length(), 3) / 3);
  }

  /// A name without the punctuation around it, for [#name(String, Collection, Function)]: an
  /// option's `--` or a single `-`, a module's `:`, a key's `:` after it.
  public static String bare(String name) {
    return name.replaceFirst("^(--?|:)", "").replaceFirst(":$", "");
  }

  /// The id in `ids` that `typed` is one edit from: an id is random, so among thousands one is
  /// often a few edits from any other, and only a slip of one key is likely.
  public static Optional<String> id(String typed, Collection<String> ids) {
    return name(typed, ids, Function.identity(), 1);
  }

  private static Optional<String> name(
      String typed, Collection<String> names, Function<String, String> key, int most) {
    var wanted = key.apply(typed);
    return names.stream()
        .filter(name -> distance(wanted, key.apply(name)) <= most)
        .min(
            Comparator.<String>comparingInt(name -> distance(wanted, key.apply(name)))
                .thenComparingInt(name -> Math.abs(key.apply(name).length() - wanted.length())));
  }

  /// The edits from `from` to `to`: a character inserted, deleted, changed, or two next to each
  /// other swapped (optimal string alignment), compared without case.
  public static int distance(String from, String to) {
    return new Edits(from.toLowerCase(Locale.ROOT), to.toLowerCase(Locale.ROOT)).all();
  }

  /// The fewest edits from each start of one name to each start of another, filled in order.
  private static final class Edits {
    private final String from;
    private final String to;
    private final int[][] fewest;

    Edits(String from, String to) {
      this.from = from;
      this.to = to;
      this.fewest = new int[from.length() + 1][to.length() + 1];
    }

    /// The edits from all of `from` to all of `to`.
    int all() {
      for (int i = 0; i <= from.length(); i++) {
        for (int j = 0; j <= to.length(); j++) {
          fewest[i][j] = i == 0 || j == 0 ? i + j : fewest(i, j);
        }
      }
      return fewest[from.length()][to.length()];
    }

    /// The fewest edits to `from`'s first `i` characters to make `to`'s first `j`, from the ones
    /// before.
    private int fewest(int i, int j) {
      int changed = from.charAt(i - 1) == to.charAt(j - 1) ? 0 : 1;
      int least =
          Math.min(
              Math.min(fewest[i - 1][j] + 1, fewest[i][j - 1] + 1), fewest[i - 1][j - 1] + changed);
      boolean swapped =
          i > 1
              && j > 1
              && from.charAt(i - 1) == to.charAt(j - 2)
              && from.charAt(i - 2) == to.charAt(j - 1);
      return swapped ? Math.min(least, fewest[i - 2][j - 2] + 1) : least;
    }
  }
}
