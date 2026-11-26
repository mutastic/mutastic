package io.mutastic.maven;

import io.mutastic.api.BuildArguments.Refused;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// The arguments a Maven build can't be given, and why: those that choose what it builds or where,
/// that stop it from building, and that change the output mutastic reads.
final class MavenArguments {
  private MavenArguments() {}

  private static final String TESTS = "mutastic chooses the tests it runs";
  private static final String WHERE =
      "mutastic chooses the build and its modules, by what it's run on";
  private static final String OUTPUT = "mutastic reads the build's output, which it changes";
  private static final String NO_BUILD = "the build wouldn't compile what mutastic runs";
  private static final String OLD =
      "it leaves out a step that builds the classes mutastic runs, so it would test old ones";
  private static final String HIDES =
      "it hides a build that failed, so mutastic would test old classes";
  private static final String OWN = "it's a setting of mutastic's own";
  private static final String GOAL = "mutastic chooses the goals it runs";

  private static final Map<String, String> SHORT =
      Map.ofEntries(
          Map.entry("f", WHERE),
          Map.entry("pl", WHERE),
          Map.entry("am", WHERE),
          Map.entry("amd", WHERE),
          Map.entry("rf", WHERE),
          Map.entry("r", WHERE),
          Map.entry("N", WHERE),
          Map.entry("q", OUTPUT),
          Map.entry("l", OUTPUT),
          Map.entry("X", OUTPUT),
          Map.entry("h", NO_BUILD),
          Map.entry("v", NO_BUILD),
          Map.entry("emp", NO_BUILD),
          Map.entry("ep", NO_BUILD),
          Map.entry("fn", HIDES));

  private static final Map<String, String> LONG =
      Map.ofEntries(
          Map.entry("file", WHERE),
          Map.entry("projects", WHERE),
          Map.entry("also-make", WHERE),
          Map.entry("also-make-dependents", WHERE),
          Map.entry("resume-from", WHERE),
          Map.entry("resume", WHERE),
          Map.entry("non-recursive", WHERE),
          Map.entry("quiet", OUTPUT),
          Map.entry("log-file", OUTPUT),
          Map.entry("debug", OUTPUT),
          Map.entry("verbose", OUTPUT),
          Map.entry("help", NO_BUILD),
          Map.entry("version", NO_BUILD),
          Map.entry("encrypt-master-password", NO_BUILD),
          Map.entry("encrypt-password", NO_BUILD),
          Map.entry("fail-never", HIDES));

  private static final Map<String, String> PROPERTIES =
      Map.of(
          "test", TESTS,
          "it.test", TESTS,
          "maven.main.skip", OLD,
          "maven.test.skip", OLD,
          "maven.resources.skip", OLD,
          "maven.ext.class.path", OWN);

  /// Options whose value can be the word after them.
  private static final Set<String> WITH_VALUE =
      Set.of(
          "D",
          "define",
          "P",
          "activate-profiles",
          "s",
          "settings",
          "gs",
          "global-settings",
          "ps",
          "project-settings",
          "t",
          "toolchains",
          "gt",
          "global-toolchains",
          "T",
          "threads",
          "b",
          "builder",
          "color");

  static Optional<Refused> refused(List<String> arguments) {
    for (int at = 0; at < arguments.size(); at++) {
      var word = arguments.get(at);
      if (!word.startsWith("-") || word.length() < 2) {
        return Optional.of(new Refused(at, Refused.WORD, GOAL));
      }
      var bare = word.substring(word.startsWith("--") ? 2 : 1);
      var property = property(bare, at + 1 < arguments.size() ? arguments.get(at + 1) : "");
      var refused =
          property.isPresent() ? refusedProperty(property.get()) : refusedOption(word, name(bare));
      if (refused.isPresent()) {
        int here = at;
        return refused.map(why -> new Refused(here, why.name(), why.reason()));
      }
      if (WITH_VALUE.contains(bare)) {
        at++; // its value
      }
    }
    return Optional.empty();
  }

  /// The property `-Dname=value` sets, or `-D name=value`, or `--define`.
  private static Optional<String> property(String bare, String next) {
    if (bare.equals("D") || bare.equals("define")) {
      return Optional.of(name(next));
    }
    if (bare.startsWith("define=")) {
      return Optional.of(name(bare.substring("define=".length())));
    }
    return bare.startsWith("D") ? Optional.of(name(bare.substring(1))) : Optional.empty();
  }

  private static String name(String setting) {
    int equals = setting.indexOf('=');
    return equals < 0 ? setting : setting.substring(0, equals);
  }

  private static Optional<Refused> refusedProperty(String name) {
    var reason = name.startsWith("mutastic.") ? OWN : PROPERTIES.get(name);
    return Optional.ofNullable(reason).map(why -> new Refused(0, "-D" + name, why));
  }

  /// Maven takes a long option by any start of it, after one dash or two, and a short one with its
  /// value against it.
  private static Optional<Refused> refusedOption(String word, String name) {
    if (WITH_VALUE.contains(name) || KNOWN.contains(name)) {
      return Optional.empty();
    }
    var shortened = word.startsWith("--") ? Optional.<String>empty() : shortened(name);
    if (shortened.isPresent()) {
      var option = shortened.get();
      return Optional.of(new Refused(0, "-" + option, String.valueOf(SHORT.get(option))));
    }
    if (!word.startsWith("--") && name.length() < 4) {
      return Optional.empty(); // a short one, -U or -nsu
    }
    return LONG.keySet().stream()
        .filter(refused -> refused.startsWith(name))
        .sorted()
        .findFirst()
        .map(refused -> new Refused(0, "--" + refused, String.valueOf(LONG.get(refused))));
  }

  /// Short options whose value can be against them, `-fpom.xml`, the longer first.
  private static final List<String> AGAINST = List.of("pl", "rf", "f", "l");

  /// The refused short option `name` is, or starts with: `-file`, though, is a long one.
  private static Optional<String> shortened(String name) {
    if (SHORT.containsKey(name)) {
      return Optional.of(name);
    }
    if (LONG.containsKey(name)) {
      return Optional.empty();
    }
    return AGAINST.stream().filter(name::startsWith).findFirst();
  }

  /// Long options of Maven's that start as a refused one could.
  private static final Set<String> KNOWN =
      Set.of(
          "fae",
          "ff",
          "fos",
          "llr",
          "fail-fast",
          "fail-at-end",
          "legacy-local-repository",
          "no-transfer-progress",
          "offline");
}
