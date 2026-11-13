package io.mutastic.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/// What a build tool says when the module asked for isn't one mutastic can test: the build's
/// modules with Java code, as the commands that test them. Gradle and Maven say it in the same
/// words.
///
/// A list of modules is in the tool's own names for them, `:app`; `:` in it is the build's own
/// directory, when it has Java code of its own.
@Internal
public final class Modules {
  /// The most commands a message offers. Every module is named, whatever their number.
  public static final int COMMANDS = 5;

  private Modules() {}

  /// `directory` is a build's root, and has no Java code of its own.
  ///
  /// @param typed the build's directory as the person would type it, for the commands
  public static String atRoot(Path directory, List<String> modules, String typed) {
    return "%s is a build's root, with no Java code of its own.\n".formatted(directory)
        + offered(modules, typed);
  }

  /// `module` of the build in `directory` has no Java code.
  public static String withoutJava(
      Path directory, String module, List<String> modules, String typed) {
    return "module %s of the build in %s has no Java code.\n".formatted(module, directory)
        + offered(modules, typed);
  }

  /// The build has no module `module`: one with a close name if there's one, else its modules. The
  /// close one is said, never run.
  public static String missing(String module, List<String> modules, String typed) {
    var headline = "the build has no module %s.\n".formatted(module);
    return closest(module, named(modules))
        .map(
            close ->
                "There's one called %s. To test it:\n%s".formatted(close, command(typed, close)))
        .map(headline::concat)
        .orElseGet(() -> headline + offered(modules, typed));
  }

  /// The modules with Java code, as commands; or what to do when there are none.
  private static String offered(List<String> modules, String typed) {
    var named = named(modules);
    if (named.isEmpty()) {
      return modules.contains(":")
          ? "Its Java code is in the build's own directory. To test it:\n" + command(typed, ":")
          : "No module of it has Java code. Run mutastic on the project with your Java code:\n"
              + "  mutastic run <project directory> [:module]";
    }
    var commands =
        named.stream()
            .limit(COMMANDS)
            .map(module -> command(typed, module))
            .collect(Collectors.joining("\n"));
    return named.size() == 1
        ? "Its module with Java code is %s. To test it:\n%s".formatted(named.getFirst(), commands)
        : "Its modules with Java code are %s. To test one:\n%s".formatted(listed(named), commands);
  }

  /// The modules but the build's own directory.
  private static List<String> named(List<String> modules) {
    return modules.stream().filter(module -> !module.equals(":")).toList();
  }

  /// `:app, :core and :web`.
  private static String listed(List<String> modules) {
    var allButLast = modules.subList(0, modules.size() - 1);
    return String.join(", ", allButLast) + " and " + modules.getLast();
  }

  /// ` mutastic run <typed> :core`, the directory left out when it's where the person is.
  private static String command(String typed, String module) {
    var words = new StringBuilder("  mutastic run");
    if (!typed.isEmpty()) {
      words.append(' ').append(typed);
    }
    if (!module.equals(":")) {
      words.append(' ').append(module);
    }
    return words.toString();
  }

  /// The one module whose name is closest to `module`'s: the same but for case, else within
  /// `max(length, 3) / 3` edits, a transposition counting as one. None when two are as close.
  static Optional<String> closest(String module, List<String> modules) {
    var wanted = module.substring(module.startsWith(":") ? 1 : 0);
    var sameButCase =
        modules.stream().filter(found -> name(found).equalsIgnoreCase(wanted)).toList();
    if (!sameButCase.isEmpty()) {
      return sameButCase.size() == 1 ? Optional.of(sameButCase.getFirst()) : Optional.empty();
    }
    int most = Math.max(wanted.length(), 3) / 3;
    var best = modules.stream().mapToInt(found -> distance(wanted, name(found))).min();
    if (best.isEmpty() || best.getAsInt() > most) {
      return Optional.empty();
    }
    var nearest =
        modules.stream().filter(found -> distance(wanted, name(found)) == best.getAsInt()).toList();
    return nearest.size() == 1 ? Optional.of(nearest.getFirst()) : Optional.empty();
  }

  private static String name(String module) {
    return module.substring(module.startsWith(":") ? 1 : 0);
  }

  /// The edits from `from` to `to`: a character inserted, deleted, changed, or two next to each
  /// other swapped (optimal string alignment), compared without case.
  static int distance(String from, String to) {
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
