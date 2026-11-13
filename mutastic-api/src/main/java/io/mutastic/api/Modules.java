package io.mutastic.api;

import java.nio.file.Path;
import java.util.List;
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

  /// The one module whose name is closest to `module`'s, by [Nearest]'s rule.
  static Optional<String> closest(String module, List<String> modules) {
    return Nearest.name(module, modules, Nearest::bare);
  }
}
