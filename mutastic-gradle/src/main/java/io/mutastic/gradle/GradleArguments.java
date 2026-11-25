package io.mutastic.gradle;

import io.mutastic.api.BuildArguments.Refused;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// The arguments a Gradle build can't be given, and why: those that choose what it builds or where,
/// that stop it from building, and that change the output mutastic reads.
final class GradleArguments {
  private GradleArguments() {}

  private static final String TESTS = "mutastic chooses the tests it runs";
  private static final String WHERE = "mutastic chooses the build, by the directory it's run on";
  private static final String OUTPUT = "mutastic reads the build's output, which it changes";
  private static final String NO_BUILD = "the build wouldn't compile what mutastic runs";
  private static final String ENDLESS = "the build would never end";
  private static final String OWN = "it's a setting of mutastic's own";
  private static final String TASK = "mutastic chooses the tasks it runs";
  private static final String LEFT_OUT =
      "it leaves out a task that builds the classes mutastic runs, so it would test old ones";
  private static final String CACHED = "mutastic's setup can't run from the configuration cache";

  /// Options by name, without dashes: Gradle takes a long one after one dash or two.
  private static final Map<String, String> REFUSED =
      Map.ofEntries(
          Map.entry("tests", TESTS),
          Map.entry("t", ENDLESS),
          Map.entry("continuous", ENDLESS),
          Map.entry("m", NO_BUILD),
          Map.entry("dry-run", NO_BUILD),
          Map.entry("task-graph", NO_BUILD),
          Map.entry("stop", NO_BUILD),
          Map.entry("status", NO_BUILD),
          Map.entry("gui", NO_BUILD),
          Map.entry("h", NO_BUILD),
          Map.entry("?", NO_BUILD),
          Map.entry("help", NO_BUILD),
          Map.entry("v", NO_BUILD),
          Map.entry("version", NO_BUILD),
          Map.entry("foreground", ENDLESS),
          Map.entry("p", WHERE),
          Map.entry("project-dir", WHERE),
          Map.entry("b", WHERE),
          Map.entry("build-file", WHERE),
          Map.entry("c", WHERE),
          Map.entry("settings-file", WHERE),
          Map.entry("q", OUTPUT),
          Map.entry("quiet", OUTPUT),
          Map.entry("w", OUTPUT),
          Map.entry("warn", OUTPUT),
          Map.entry("i", OUTPUT),
          Map.entry("info", OUTPUT),
          Map.entry("d", OUTPUT),
          Map.entry("debug", OUTPUT),
          Map.entry("console", OUTPUT),
          Map.entry("configuration-cache", CACHED));

  /// Options whose value can be the word after them.
  private static final Set<String> WITH_VALUE =
      Set.of(
          "x",
          "exclude-task",
          "P",
          "project-prop",
          "D",
          "system-prop",
          "I",
          "init-script",
          "g",
          "gradle-user-home",
          "project-cache-dir",
          "include-build",
          "max-workers",
          "warning-mode",
          "priority",
          "F",
          "dependency-verification",
          "M",
          "write-verification-metadata");

  private static final Set<String> PROPERTIES = Set.of("P", "project-prop", "D", "system-prop");

  /// The first argument refused, for a run of `suite`.
  static Optional<Refused> refused(List<String> arguments, String suite) {
    for (int at = 0; at < arguments.size(); at++) {
      var argument = Argument.of(arguments.get(at));
      if (argument.isEmpty()) {
        return Optional.of(new Refused(arguments.get(at), TASK));
      }
      var read = argument.get();
      var value = read.value();
      if (value.isEmpty() && WITH_VALUE.contains(read.name()) && at + 1 < arguments.size()) {
        at++;
        value = Optional.of(arguments.get(at));
      }
      var refused = refused(read, value.orElse(""), suite);
      if (refused.isPresent()) {
        return refused;
      }
    }
    return Optional.empty();
  }

  private static Optional<Refused> refused(Argument read, String value, String suite) {
    var reason = REFUSED.get(read.name());
    if (reason != null) {
      return Optional.of(new Refused(read.typed(), reason));
    }
    if (PROPERTIES.contains(read.name()) && value.startsWith("mutastic")) {
      return Optional.of(new Refused(read.typed() + property(value), OWN));
    }
    if (Set.of("x", "exclude-task").contains(read.name()) && builds(value, suite)) {
      return Optional.of(new Refused("%s %s".formatted(read.typed(), value), LEFT_OUT));
    }
    // `-mq`: options of one letter given together.
    return read.together().stream()
        .filter(REFUSED::containsKey)
        .findFirst()
        .map(letter -> new Refused("-" + letter, String.valueOf(REFUSED.get(letter))));
  }

  /// A property's name, without its value.
  private static String property(String value) {
    return value.replaceFirst("=.*$", "");
  }

  /// `--exclude-task=:app:classes`: as typed up to its value, its name, and the value with it.
  private record Argument(
      String typed, String name, Optional<String> value, List<String> together) {
    /// Empty for a word that isn't an option: a task.
    static Optional<Argument> of(String word) {
      if (!word.startsWith("-") || word.length() < 2) {
        return Optional.empty();
      }
      boolean isLong = word.startsWith("--");
      var bare = word.substring(isLong ? 2 : 1);
      var name = property(bare);
      if (isLong || REFUSED.containsKey(name) || WITH_VALUE.contains(name)) {
        var value =
            name.equals(bare)
                ? Optional.<String>empty()
                : Optional.of(bare.substring(name.length() + 1));
        return Optional.of(new Argument((isLong ? "--" : "-") + name, name, value, List.of()));
      }
      return Optional.of(letters(bare));
    }

    /// `-xclasses` or `-Pkey=value`, an option of one letter with its value against it; else `-sm`,
    /// several together.
    private static Argument letters(String bare) {
      var letter = bare.substring(0, 1);
      if (WITH_VALUE.contains(letter)) {
        return new Argument("-" + letter, letter, Optional.of(bare.substring(1)), List.of());
      }
      var together = bare.chars().mapToObj(Character::toString).toList();
      return new Argument("-" + bare, bare, Optional.empty(), together);
    }
  }

  /// The languages whose code a build compiles.
  private static final List<String> LANGUAGES = List.of("Java", "Kotlin", "Groovy", "Scala");

  /// A task name's words, as Gradle splits it to match one written short: `compileJava` is
  /// `compile` and `Java`.
  private static final Pattern WORD = Pattern.compile("\\p{Upper}?[^\\p{Upper}-]*-?");

  /// Whether leaving out the task `path` names, in full or short (`:app:compileJava`, `cJ`), leaves
  /// out one that builds classes or resources a run uses: a compile task, the lifecycle tasks that
  /// pull those in, or the jar another module's are used from.
  static boolean builds(String path, String suite) {
    var task = path.substring(path.lastIndexOf(':') + 1);
    if (task.equals("test") || task.isEmpty()) {
      return false; // the task itself, though it starts testClasses
    }
    if (task.startsWith("compile") || task.endsWith("Classes") || task.startsWith("mutastic")) {
      return true;
    }
    var shortened = shortened(task);
    return building(suite).anyMatch(name -> shortened.matcher(name).matches());
  }

  /// `cJ` as Gradle reads it: each word the start of one of the name's, in order. Of a name that
  /// goes on past them, any: refusing one Gradle would take for another task is the safe side.
  private static Pattern shortened(String task) {
    var words = WORD.matcher(task).results().map(result -> result.group().replace("-", ""));
    return Pattern.compile(
        words
            .filter(word -> !word.isEmpty())
            .map(word -> "(?i:" + Pattern.quote(word.substring(0, 1)) + ")" + rest(word))
            .collect(Collectors.joining("[\\p{Lower}\\d]*", "", ".*")));
  }

  private static String rest(String word) {
    return word.length() == 1 ? "" : Pattern.quote(word.substring(1).toLowerCase(Locale.ROOT));
  }

  private static Stream<String> building(String suite) {
    var sets = Stream.of("", "Test", capitalised(suite)).distinct().toList();
    var compiling =
        sets.stream()
            .flatMap(set -> LANGUAGES.stream().map(language -> "compile" + set + language));
    var resources = sets.stream().map(set -> "process" + set + "Resources");
    var named = Stream.of("classes", "testClasses", suite + "Classes", "jar");
    return Stream.of(compiling, resources, named).flatMap(each -> each);
  }

  private static String capitalised(String name) {
    return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
  }
}
