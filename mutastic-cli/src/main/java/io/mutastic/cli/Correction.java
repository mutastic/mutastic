package io.mutastic.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/// What to write in place of a mistyped part of a command line: an error says the close name as a
/// statement, then the person's own command with only that part changed, to copy. Never run.
///
/// @param typed the arguments as typed: one, or an option and the value after it
/// @param instead the one argument to write in their place
record Correction(List<String> typed, String instead) {
  Correction {
    typed = List.copyOf(typed);
  }

  /// `typed`, one argument, as `instead`.
  static Correction of(String typed, String instead) {
    return new Correction(List.of(typed), instead);
  }

  /// A word that needs no quotes in a shell.
  private static final Pattern PLAIN = Pattern.compile("[\\w./:=,@%+-]+");

  /// `args`, the command line after `mutastic`, corrected: empty when what was typed isn't in it,
  /// as when a build plugin passed the arguments.
  Optional<String> in(List<String> args) {
    var corrected = new ArrayList<>(args);
    int at = java.util.Collections.indexOfSubList(args, typed);
    if (at >= 0) {
      corrected.subList(at, at + typed.size()).clear();
      corrected.add(at, instead);
    } else if (typed.size() != 1 || !replacedWithin(corrected)) {
      return Optional.empty();
    }
    return Optional.of(String.join(" ", corrected.stream().map(Correction::quoted).toList()));
  }

  /// One item of a list in an argument, `m855c8e3d` in `--only=m2e491c40,m855c8e3d`, corrected in
  /// place: whether there was one.
  private boolean replacedWithin(List<String> args) {
    var item =
        Pattern.compile(
            "(?<before>^-?|[=,]-?)" + Pattern.quote(typed.getFirst()) + "(?<after>$|,)");
    for (int i = 0; i < args.size(); i++) {
      var matcher = item.matcher(args.get(i));
      if (matcher.find()) {
        args.set(
            i,
            matcher.replaceFirst(
                java.util.regex.Matcher.quoteReplacement(
                    matcher.group("before") + instead + matcher.group("after"))));
        return true;
      }
    }
    return false;
  }

  /// `word` as a shell reads it back: in single quotes unless it needs none.
  static String quoted(String word) {
    return PLAIN.matcher(word).matches() ? word : "'" + word.replace("'", "'\\''") + "'";
  }
}
