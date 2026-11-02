package io.mutastic.report;

import java.util.ArrayList;
import java.util.List;

/// How a person runs mutastic on the project a run was on, typed from where they ran it: the
/// `mutastic` command, or the Maven plugin's goals. Every command the output offers is written by
/// one, so it works as it's copied.
///
/// @param plugin how Maven names the plugin's goals: `mutastic`, its prefix, when the build
///   declares it, else its coordinates, `io.mutastic:mutastic-maven-plugin:0.1.0`; empty for the
///   command line
/// @param target the project as the command line names it, `samples/pricing :api`; empty when it's
///   the directory mutastic ran in and its root project
public record Invocation(Form form, String plugin, List<String> target) {
  /// Where mutastic was started from.
  public enum Form {
    /// `mutastic verify m855c8e3c samples/pricing :api`.
    COMMAND_LINE,
    /// `mvn mutastic:verify -Dmutastic.mutant=m855c8e3c -f samples/pricing -pl api`: each option a
    /// `mutastic.` property of the same name, and the mutant's id `mutastic.mutant`.
    MAVEN
  }

  /// The `mutastic` command, on the root project of the directory it runs in.
  public static final Invocation HERE = new Invocation(Form.COMMAND_LINE, "", List.of());

  /// How Maven sets one of mutastic's options: `-Dmutastic.only=m855c8e3c`.
  private static final String PROPERTY = "-Dmutastic.";

  public Invocation {
    target = List.copyOf(target);
  }

  /// `mutastic`, `maven` or `maven:<plugin>` (a build plugin's `--commands`), for `target`.
  public static Invocation of(String commands, List<String> target) {
    if (commands.equals("mutastic")) {
      return new Invocation(Form.COMMAND_LINE, "", target);
    }
    if (commands.equals("maven") || commands.startsWith("maven:")) {
      var plugin = commands.equals("maven") ? "mutastic" : commands.substring("maven:".length());
      return new Invocation(Form.MAVEN, plugin, target);
    }
    throw new IllegalArgumentException(
        "unknown commands %s (one of mutastic, maven, maven:<plugin>)".formatted(commands));
  }

  /// `words`, mutastic's own arguments (`verify m855c8e3c`, `run --only=m855c8e3c`), written for
  /// this form and project, each word quoted if a shell needs it to be.
  public String command(List<String> words) {
    return String.join(" ", form == Form.MAVEN ? maven(words) : commandLine(words));
  }

  private List<String> commandLine(List<String> words) {
    var all = new ArrayList<>(List.of("mutastic"));
    words.forEach(word -> all.add(quoted(word)));
    target.forEach(word -> all.add(quoted(word)));
    return all;
  }

  /// `mvn mutastic:<command>`, then each option as a property, then the project: `-f` for its
  /// directory and `-pl` for its module.
  private List<String> maven(List<String> words) {
    var all = new ArrayList<>(List.of("mvn", plugin + ":" + words.getFirst()));
    for (String word : words.subList(1, words.size())) {
      all.add(quoted(property(word)));
    }
    for (String word : target) {
      all.addAll(
          word.startsWith(":")
              ? List.of("-pl", quoted(word.substring(1)))
              : List.of("-f", quoted(word)));
    }
    return all;
  }

  /// `--tests-from=:app` → `-Dmutastic.testsFrom=:app`, `--prune` → `-Dmutastic.prune`, and a
  /// mutant's id → `-Dmutastic.mutant=m855c8e3c`.
  static String property(String word) {
    if (!word.startsWith("--")) {
      return PROPERTY + "mutant=" + word;
    }
    int equals = word.indexOf('=');
    var name = equals < 0 ? word.substring(2) : word.substring(2, equals);
    return PROPERTY + camelCase(name) + (equals < 0 ? "" : word.substring(equals));
  }

  /// The other way: `-Dmutastic.testsFrom=:app` → `--tests-from=:app`, `-Dmutastic.prune` or
  /// `-Dmutastic.prune=true` → `--prune`, and `-Dmutastic.mutant=m855c8e3c` → the id. Empty for
  /// anything else.
  public static java.util.Optional<String> option(String property) {
    if (!property.startsWith(PROPERTY)) {
      return java.util.Optional.empty();
    }
    var assignment = property.substring(PROPERTY.length());
    int equals = assignment.indexOf('=');
    var name = equals < 0 ? assignment : assignment.substring(0, equals);
    var value = equals < 0 ? "true" : assignment.substring(equals + 1);
    if (name.equals("mutant")) {
      return java.util.Optional.of(value);
    }
    var option =
        "--" + name.replaceAll("(?<upper>[A-Z])", "-${upper}").toLowerCase(java.util.Locale.ROOT);
    return java.util.Optional.of(value.equals("true") ? option : option + "=" + value);
  }

  /// `tests-from` → `testsFrom`.
  private static String camelCase(String name) {
    var parts = name.split("-", -1);
    var camel = new StringBuilder(parts[0]);
    for (String part : List.of(parts).subList(1, parts.length)) {
      camel.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    return camel.toString();
  }

  /// A word with a space or a quote, quoted for a shell. Not a template the person fills in, `<id>`
  /// or `--reason="<why>"`: quoting it wouldn't help them.
  static String quoted(String word) {
    return word.matches("[A-Za-z0-9_./:@%+=,-]+") || word.matches(".*<[a-z]+>.*")
        ? word
        : "'" + word.replace("'", "'\\''") + "'";
  }
}
