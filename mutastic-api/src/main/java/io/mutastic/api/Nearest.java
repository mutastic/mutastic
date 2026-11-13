package io.mutastic.api;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/// The one name close to a mistyped one, for an error to say "There's one called …": a command, an
/// option, a value, a key in a file, a module or an id. Said, never acted on: the person or agent
/// runs it, so a guess must be likely, and two as close are no guess at all.
///
/// The rule, the same in both editions: the same name but for case; else the closest within
/// `max(length, 3) / 3` edits, where an edit inserts, deletes or changes a character or swaps two
/// next to each other. Of two as close, the one with the same letters is the likelier slip, so
/// `tset` is `test`, not `set`; otherwise two as close are neither.
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
    var sameButCase =
        names.stream().filter(name -> key.apply(name).equalsIgnoreCase(wanted)).toList();
    if (!sameButCase.isEmpty()) {
      return only(sameButCase);
    }
    var best = names.stream().mapToInt(name -> distance(wanted, key.apply(name))).min();
    if (best.isEmpty() || best.getAsInt() > most) {
      return Optional.empty();
    }
    var closest =
        names.stream()
            .filter(name -> distance(wanted, key.apply(name)) == best.getAsInt())
            .toList();
    var sameLetters =
        closest.stream().filter(name -> letters(key.apply(name)).equals(letters(wanted))).toList();
    return sameLetters.isEmpty() ? only(closest) : only(sameLetters);
  }

  /// The letters of a name in order, without case: the same for a name with two of them swapped.
  private static String letters(String name) {
    var letters = name.toLowerCase(Locale.ROOT).toCharArray();
    java.util.Arrays.sort(letters);
    return new String(letters);
  }

  /// The one name, unless there are several, which can't be told apart.
  private static Optional<String> only(List<String> names) {
    return names.stream().distinct().count() == 1
        ? Optional.of(names.getFirst())
        : Optional.empty();
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
