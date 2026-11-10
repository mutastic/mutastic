package io.mutastic.report;

import java.util.ArrayList;
import java.util.List;

/// How a person runs mutastic on the project a run was on, typed from where they ran it: the
/// `mutastic` command, the Maven plugin's goals or the Gradle plugin's tasks. Every command the
/// output offers is written by one, so it works as it's copied.
///
/// @param launcher how the build runs it: for Maven, the plugin as goals name it, `mutastic` (its
///   prefix) when the build declares it, else its coordinates,
///   `io.mutastic:mutastic-maven-plugin:0.2.0`; for Gradle, the command, `./gradlew` or `gradle`;
///   empty for the command line
/// @param target the project as the command line names it, `samples/pricing :api`, and its test
///   suite when it isn't the default, `--suite=integrationTest`; empty when it's the directory
///   mutastic ran in, its root project and its default suite
public record Invocation(Form form, String launcher, List<String> target) {
  /// Where mutastic was started from.
  public enum Form {
    /// `mutastic verify m855c8e3c samples/pricing :api`.
    COMMAND_LINE,
    /// `mvn mutastic:verify -Dmutastic.mutant=m855c8e3c -f samples/pricing -pl api`: each option a
    /// `mutastic.` property of the same name, and the mutant's id `mutastic.mutant`.
    MAVEN,
    /// `./gradlew :api:mutasticVerify -Pmutastic.mutant=m855c8e3c -p samples/pricing`: the same
    /// properties, set as Gradle sets them, on the module's task.
    GRADLE
  }

  /// The `mutastic` command, on the root project of the directory it runs in.
  public static final Invocation HERE = new Invocation(Form.COMMAND_LINE, "", List.of());

  /// How Maven sets one of mutastic's options: `-Dmutastic.only=m855c8e3c`.
  private static final String MAVEN_PROPERTY = "-Dmutastic.";

  /// How Gradle does: `-Pmutastic.only=m855c8e3c`.
  private static final String GRADLE_PROPERTY = "-Pmutastic.";

  public Invocation {
    target = List.copyOf(target);
  }

  /// `mutastic`, `maven`, `maven:<plugin>`, `gradle` or `gradle:<command>` (a build plugin's
  /// `--commands`), for `target`.
  public static Invocation of(String commands, List<String> target) {
    if (commands.equals("mutastic")) {
      return new Invocation(Form.COMMAND_LINE, "", target);
    }
    if (commands.equals("maven") || commands.startsWith("maven:")) {
      var plugin = commands.equals("maven") ? "mutastic" : commands.substring("maven:".length());
      return new Invocation(Form.MAVEN, plugin, target);
    }
    if (commands.equals("gradle") || commands.startsWith("gradle:")) {
      var gradle = commands.equals("gradle") ? "gradle" : commands.substring("gradle:".length());
      return new Invocation(Form.GRADLE, gradle, target);
    }
    throw new IllegalArgumentException(
        "unknown commands %s (one of mutastic, maven, maven:<plugin>, gradle, gradle:<command>)"
            .formatted(commands));
  }

  /// `words`, mutastic's own arguments (`verify m855c8e3c`, `run --only=m855c8e3c`), written for
  /// this form and project, each word quoted if a shell needs it to be.
  public String command(List<String> words) {
    var written =
        switch (form) {
          case COMMAND_LINE -> commandLine(words);
          case MAVEN -> maven(words);
          case GRADLE -> gradle(words);
        };
    return String.join(" ", written);
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
    var all = new ArrayList<>(List.of("mvn", launcher + ":" + words.getFirst()));
    for (String word : words.subList(1, words.size())) {
      all.add(quoted(MAVEN_PROPERTY + property(word)));
    }
    for (String word : target) {
      if (word.startsWith("--")) {
        all.add(quoted(MAVEN_PROPERTY + property(word))); // `--suite=…`, part of the project
      } else {
        all.addAll(
            word.startsWith(":")
                ? List.of("-pl", quoted(word.substring(1)))
                : List.of("-f", quoted(word)));
      }
    }
    return all;
  }

  /// `./gradlew :api:mutasticVerify` (`:mutasticVerify` for the root project), then each option as
  /// a property, then `-p` for the project's directory.
  private List<String> gradle(List<String> words) {
    var module = target.stream().filter(word -> word.startsWith(":")).findFirst().orElse("");
    var task = module + ":mutastic" + Character.toUpperCase(words.getFirst().charAt(0));
    var all = new ArrayList<>(List.of(launcher, task + words.getFirst().substring(1)));
    for (String word : words.subList(1, words.size())) {
      all.add(quoted(GRADLE_PROPERTY + property(word)));
    }
    for (String word : target) {
      if (word.startsWith("--")) {
        all.add(quoted(GRADLE_PROPERTY + property(word))); // `--suite=…`, part of the project
      } else if (!word.startsWith(":")) {
        all.addAll(List.of("-p", quoted(word)));
      }
    }
    return all;
  }

  /// `--tests-from=:app` → `testsFrom=:app`, `--prune` → `prune`, and a mutant's id →
  /// `mutant=m855c8e3c`: the property, after `mutastic.`.
  static String property(String word) {
    if (!word.startsWith("--")) {
      return "mutant=" + word;
    }
    int equals = word.indexOf('=');
    var name = equals < 0 ? word.substring(2) : word.substring(2, equals);
    return camelCase(name) + (equals < 0 ? "" : word.substring(equals));
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
    return word.matches(PLAIN) || word.matches(".*<[a-z]+>.*")
        ? word
        : "'" + word.replace("'", "'\\''") + "'";
  }

  /// The characters a word can have and not need quotes. On Windows, a path's `\` and the `~` of a
  /// short name (`C:\Users\RUNNER~1`) are typed as they are.
  private static final String PLAIN =
      java.io.File.separatorChar == '\\' ? "[A-Za-z0-9_./:@%+=,~\\\\-]+" : "[A-Za-z0-9_./:@%+=,-]+";
}
