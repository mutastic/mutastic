package io.mutastic.cli;

import java.util.List;
import java.util.Set;

/// One of mutastic's options: how it's written, what it takes, what it's for, and how the build
/// plugins set it. [Options] lists them all, and the parser, `--help` and the docs reference read
/// them from there, so none of them can name an option another doesn't know.
///
/// @param name the option as typed, without a value: `--threshold`
/// @param value what its value is, as help shows it: `N`, `REF`; empty for a flag
/// @param choices the values it takes, when it takes only some; empty for any
/// @param help what it does, in a line, with its default when it has one
/// @param commands the commands it means something to
/// @param property its name as a build plugin's `mutastic.` property, `threshold`; empty when a
///   plugin can't set it
/// @param buildSetting whether the plugins also take it as a build-file setting, not only as a
///   property on the command line
/// @param environment the environment variables that set it when it isn't given, if any
/// @param example a command that uses it, for help and the docs; empty for those people don't use
record Option(
    String name,
    Kind kind,
    Group group,
    String value,
    List<String> choices,
    String help,
    Set<Main.Subcommand> commands,
    String property,
    boolean buildSetting,
    String environment,
    String example) {

  Option {
    choices = List.copyOf(choices);
    commands = Set.copyOf(commands);
  }

  /// The mutant a command that takes one is shown with, when an example is moved onto it.
  private static final String SHOWN_ID = "m855c8e3c";

  /// Its example as `command` would be given it: the example is written once, for one of the
  /// option's commands, and shown on each of their pages as that page's own command. A mutant id
  /// comes with it, or goes, as the command takes one or doesn't.
  String exampleFor(Main.Subcommand command) {
    var written = Main.Subcommand.in(example);
    if (written.isEmpty() || written.get() == command) {
      return example;
    }
    var from = written.get();
    var rest = example.substring(("mutastic " + from.word()).length()).strip();
    if (from.takesMutantId && !command.takesMutantId) {
      rest = rest.replaceFirst("^\\S+\\s*", "");
    } else if (!from.takesMutantId && command.takesMutantId) {
      rest = (SHOWN_ID + " " + rest).strip();
    }
    return ("mutastic " + command.word() + " " + rest).strip();
  }

  /// Whether, and how, it takes a value.
  enum Kind {
    /// `--confirm-catches`: on when given.
    FLAG,
    /// `--threshold=60` or `--threshold 60`.
    VALUE,
    /// `--since` alone, or `--since=main`: never the next word, which may be the directory.
    OPTIONAL_VALUE,
    /// `--tests-from=:app`, given once for each value.
    REPEATED
  }

  /// Where help lists it.
  enum Group {
    RUNNING("Running"),
    OUTPUT("Output"),
    IGNORING("Ignoring"),
    GATE("Gate"),
    /// Passed by the build plugins, which run inside the build; never shown in help.
    BUILD_PLUGINS("");

    final String heading;

    Group(String heading) {
      this.heading = heading;
    }
  }

  /// `--threshold=N`, `--since[=REF]`, `--confirm-catches`: as help writes it.
  String synopsis() {
    return switch (kind) {
      case FLAG -> name;
      case OPTIONAL_VALUE -> name + "[=" + value + "]";
      case VALUE, REPEATED -> name + "=" + value;
    };
  }

  /// Whether it's set by turning a property off: `--no-suggestions` is `suggestions=false`.
  boolean negated() {
    return name.startsWith("--no-");
  }
}
