package io.mutastic.cli;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;
import tools.jackson.databind.JsonNode;

/// What the command line `current` declares breaks of what a released one, `released`, did: each in
/// `commands.json`'s shape, as [DocsModel] writes it.
///
/// A command, an option, a choice, a variable or an exit status may be added; none may be taken
/// away. An option keeps its kind and the commands it's on, and how each runner spells it: its
/// environment variable, and its Maven and Gradle property and build setting, since a build file or
/// a CI job says it that way.
final class CommandLineCompatibility {
  private final List<String> breaks = new ArrayList<>();

  private CommandLineCompatibility() {}

  /// Each promise `current` breaks of `released`'s, in words: empty when it keeps them.
  static List<String> breaks(JsonNode released, JsonNode current) {
    var compatibility = new CommandLineCompatibility();
    compatibility.commands(released.path("commands"), current.path("commands"));
    compatibility.options(released.path("options"), current.path("options"));
    compatibility.gone(released.path("environment"), current.path("environment"), "the variable");
    compatibility.gone(released.path("exitCodes"), current.path("exitCodes"), "the exit status");
    return compatibility.breaks;
  }

  private void commands(JsonNode released, JsonNode current) {
    for (JsonNode command : released.values()) {
      var name = command.path("name").asString();
      var now = named(current, name);
      if (now.isEmpty()) {
        breaks.add("the command `%s` is gone".formatted(name));
        continue;
      }
      for (String option : missing(command.path("options"), now.get().path("options"))) {
        breaks.add("`%s` no longer takes `%s`".formatted(name, option));
      }
    }
  }

  private void options(JsonNode released, JsonNode current) {
    for (JsonNode option : released.values()) {
      var name = option.path("name").asString();
      var now = named(current, name);
      if (now.isEmpty()) {
        breaks.add("the option `%s` is gone".formatted(name));
        continue;
      }
      var kind = option.path("kind").asString();
      if (!kind.equals(now.get().path("kind").asString())) {
        breaks.add(
            "`%s` was a `%s` option, and is a `%s` one"
                .formatted(name, kind, now.get().path("kind").asString()));
      }
      for (String choice : missing(option.path("choices"), now.get().path("choices"))) {
        breaks.add("`%s` no longer takes `%s`".formatted(name, choice));
      }
      for (String command : missing(option.path("commands"), now.get().path("commands"))) {
        breaks.add("`%s` is no longer an option of `%s`".formatted(name, command));
      }
      kept(name, "its variable", option.path("environment"), now.get().path("environment"));
      for (String runner : List.of("maven", "gradle")) {
        for (String spelling : List.of("property", "setting")) {
          kept(
              name,
              "its %s %s".formatted(runner, spelling),
              option.path(runner).path(spelling),
              now.get().path(runner).path(spelling));
        }
      }
    }
  }

  /// A spelling the release had is still the same: one it didn't have may be added.
  private void kept(String option, String what, JsonNode then, JsonNode later) {
    var had = then.isString() ? then.asString() : "";
    var has = later.isString() ? later.asString() : "";
    if (!had.isEmpty() && !had.equals(has)) {
      breaks.add(
          has.isEmpty()
              ? "`%s` no longer has %s, `%s`".formatted(option, what, had)
              : "`%s` changed %s from `%s` to `%s`".formatted(option, what, had, has));
    }
  }

  private void gone(JsonNode released, JsonNode current, String what) {
    for (String name : missing(released, current)) {
      breaks.add("%s `%s` is gone".formatted(what, name));
    }
  }

  /// The entry of `list` called `name`.
  private static Optional<JsonNode> named(JsonNode list, String name) {
    return list.values().stream()
        .filter(entry -> entry.path("name").asString().equals(name))
        .findFirst();
  }

  /// The names in `released` that `current` doesn't have: of strings, or of objects with a `name`.
  private static SequencedSet<String> missing(JsonNode released, JsonNode current) {
    var now = names(current);
    var gone = new LinkedHashSet<String>();
    for (String name : names(released)) {
      if (!now.contains(name)) {
        gone.add(name);
      }
    }
    return gone;
  }

  private static SequencedSet<String> names(JsonNode list) {
    var names = new LinkedHashSet<String>();
    list.values().stream()
        .map(entry -> entry.isString() ? entry.asString() : entry.path("name").asString())
        .forEach(names::add);
    return names;
  }
}
