package io.mutastic.cli;

import io.mutastic.api.BuildTool;
import io.mutastic.engine.Engine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// A command that couldn't do what it was asked: what stderr says, the exit status it ends with,
/// and the same as a `run-failed` event for an agent or a tool reading `--format=agent` or `jsonl`.
///
/// The event is the Rust edition's, field for field (mutastic-rs's `failure::event`), and its codes
/// are that edition's where the failure is the same, so a tool reads either.
///
/// @param code what went wrong, a stable name: `unknown-option`, `build-failed`, `red-baseline`
/// @param said every line stderr gets, each ending in a newline
record Failure(int exit, String code, String said) {
  /// What a usage error is called when nothing names it more closely: the Rust edition's code for
  /// an option's value it can't use.
  static final String USAGE = "option-value";

  /// What an error without a code of its own is called.
  static final String OTHER = "other";

  /// Every code a failure has, each with its entry on the errors page (reference/errors).
  static final List<String> CODES =
      List.of(
          "unknown-command",
          "unknown-option",
          "option-not-taken",
          "option-value",
          "needs-id",
          "extra-words",
          "no-baseline",
          "shards",
          "config",
          "environment",
          "git",
          "unknown-id",
          "no-directory",
          "no-project",
          "module-missing",
          "module-without-java",
          "no-build-tool",
          "unknown-suite",
          "build-failed",
          "build-jdk",
          "red-baseline",
          "tests-stopped",
          "tests-timed-out",
          "no-tests",
          "nothing-covered",
          "disk",
          OTHER,
          "internal");

  /// What `failure`, thrown running the command line `args`, says, and its exit status.
  static Failure of(RuntimeException failure, List<String> args, Map<String, String> env) {
    var message = String.valueOf(failure.getMessage());
    var headline = "mutastic: " + message + "\n";
    return switch (failure) {
      case Main.UsageException usage -> usage(usage, headline, args);
      case Main.ConfigException config -> new Failure(Main.USAGE, config.code(), headline);
      case Engine.UnknownMutant unknown ->
          new Failure(Main.USAGE, "unknown-id", headline + closeId(unknown, args));
      case BuildTool.SetupFailed setup ->
          withHint(
              setup.code().equals("setup") ? OTHER : setup.code(),
              message + elsewhere(setup, message, args),
              setup,
              env);
      case Engine.NoTestsRan none -> withHint("no-tests", message, none, env);
      case Engine.NothingCovered nothing -> withHint("nothing-covered", message, nothing, env);
      case Engine.CantRun cantRun -> withHint(OTHER, message, cantRun, env);
      case Shares.Refused refused -> withHint("shards", message, refused, env);
      case Engine.BaselineFailed red -> new Failure(Main.TESTS_FAIL, red.code(), headline);
      case java.io.UncheckedIOException disk -> withHint("disk", Disk.said(disk), disk, env);
      default ->
          new Failure(Main.INTERNAL, "internal", "mutastic: internal error: " + failure + "\n");
    };
  }

  /// A mistake in the command line: the command corrected, when it's a close name; else the message
  /// ends in what to run, if anything.
  private static Failure usage(Main.UsageException usage, String headline, List<String> args) {
    var message = String.valueOf(usage.getMessage());
    return new Failure(
        Main.USAGE,
        usage.code().equals(USAGE) ? usageCode(message) : usage.code(),
        headline + corrected(usage.correction(), args).orElse(""));
  }

  /// The command with the id a slip away from the one asked for, if there's one; else the command
  /// that lists the project's mutants.
  private static String closeId(Engine.UnknownMutant unknown, List<String> args) {
    if (unknown.closest().isEmpty()) {
      return "  mutastic " + String.join(" ", with("mutants", where(args))) + "\n";
    }
    return corrected(unknown.closest().map(id -> Correction.of(unknown.id(), id)), args).orElse("");
  }

  /// A directory with no build in it or above it: the command again, on the project's directory.
  private static String elsewhere(BuildTool.SetupFailed setup, String message, List<String> args) {
    if (!setup.code().equals("no-project") || !message.endsWith(":") || args.isEmpty()) {
      return "";
    }
    var words = new ArrayList<String>();
    var directory = false;
    for (String arg : args) {
      if (!directory && !arg.startsWith("-") && !arg.startsWith(":") && !named(arg, args)) {
        words.add("<project directory>");
        directory = true;
      } else {
        words.add(Correction.quoted(arg));
      }
    }
    if (!directory) {
      words.add(command(args).size(), "<project directory>");
    }
    return "\n  mutastic " + String.join(" ", words);
  }

  /// Whether `arg` is one of the command's own words, or a mutant's id.
  private static boolean named(String arg, List<String> args) {
    return command(args).contains(arg) || arg.matches("m[0-9a-f]{8}(,m[0-9a-f]{8})*");
  }

  /// The words that name the command and the mutants it's on: `verify m855c8e3c`, `gate open`.
  private static List<String> command(List<String> args) {
    int words = Main.Subcommand.at(args).map(command -> Main.Subcommand.length(args)).orElse(1);
    var named = new ArrayList<>(args.subList(0, Math.min(words, args.size())));
    for (String arg : args.subList(named.size(), args.size())) {
      if (!arg.matches("m[0-9a-f]{8}(,m[0-9a-f]{8})*")) {
        break;
      }
      named.add(arg);
    }
    return named;
  }

  /// The directory and module the command line names, as typed, quoted for a shell.
  private static List<String> where(List<String> args) {
    return args.subList(command(args).size(), args.size()).stream()
        .filter(arg -> !arg.startsWith("-"))
        .map(Correction::quoted)
        .toList();
  }

  private static List<String> with(String first, List<String> rest) {
    var words = new ArrayList<String>();
    words.add(first);
    words.addAll(rest);
    return words;
  }

  /// A usage error's status, and the Codex sandbox's hint when the sandbox is why.
  private static Failure withHint(
      String code, String message, RuntimeException failure, Map<String, String> env) {
    var hint = CodexSandbox.hint(failure, env).map(said -> said + "\n").orElse("");
    return new Failure(Main.USAGE, code, "mutastic: " + message + "\n" + hint);
  }

  /// The Rust edition's code for a usage error, by what it says.
  private static String usageCode(String message) {
    if (message.contains("mutant id") || message.contains("needs a reason")) {
      return "needs-id";
    }
    if (message.contains("--fail-on-new needs a baseline")) {
      return "no-baseline";
    }
    if (message.contains("--from-shards")) {
      return "shards";
    }
    return message.contains(" doesn't take ") ? "extra-words" : USAGE;
  }

  /// The command line with the correction made, to copy: none when a build plugin passed the
  /// arguments, which the person wrote in its own form.
  private static Optional<String> corrected(Optional<Correction> correction, List<String> args) {
    if (args.stream().anyMatch(arg -> arg.startsWith("--setup"))) {
      return Optional.empty();
    }
    return correction.flatMap(each -> each.in(args)).map(fixed -> "  mutastic " + fixed + "\n");
  }

  /// How a line that is a command starts: mutastic's, a build tool's, or the look at a log.
  private static final Pattern COMMAND =
      Pattern.compile(
          "^  (?:mutastic |\\S*gradlew(?:\\.bat)? |gradle |\\S*mvnw(?:\\.cmd)? |mvn |tail )");

  /// A close name the message offers: `There's one called --format.`
  private static final Pattern OFFERED =
      Pattern.compile("There's one called (?<name>\\S+?)[.,]?(?=\\s|$)");

  /// What went wrong, as the first line says it, without `mutastic: `.
  String message() {
    return said.lines().findFirst().orElse("").replaceFirst("^mutastic: ", "");
  }

  /// The commands the message ends in, each on a line of its own, set in by two spaces, with how
  /// sure mutastic is of each, as rustc grades a suggestion: `exact` runs as it is; `placeholders`
  /// has a part in `<…>` to fill in first; `maybe` is a close name, a guess at what was meant, to
  /// check before running it.
  List<Map<String, Object>> help() {
    var offered = new ArrayList<String>();
    var names = OFFERED.matcher(said);
    while (names.find()) {
      offered.add(names.group("name").replaceFirst(":$", ""));
    }
    var commands = new ArrayList<Map<String, Object>>();
    for (String line : said.lines().toList()) {
      if (COMMAND.matcher(line).find()) {
        var command = line.strip();
        var help = new LinkedHashMap<String, Object>();
        help.put("applicability", applicability(command, offered));
        help.put("command", command);
        commands.add(help);
      }
    }
    return commands;
  }

  private static String applicability(String command, List<String> offered) {
    if (command.contains("<") || command.contains("…")) {
      return "placeholders";
    }
    var words = List.of(command.split(" ", -1));
    return words.stream().anyMatch(word -> offered.stream().anyMatch(name -> names(word, name)))
        ? "maybe"
        : "exact";
  }

  /// Whether `word` of a command is `name`, or gives it as an option's value or in a list.
  private static boolean names(String word, String name) {
    return word.equals(name)
        || word.startsWith(name + "=")
        || word.endsWith("=" + name)
        || word.contains("," + name);
  }

  /// The `run-failed` event's fields, after its schema, edition and reason, for a command run on
  /// `directory` as typed: with its whole path, or null when there's no such directory.
  Map<String, Object> event(String directory) {
    var where = new LinkedHashMap<String, @Nullable Object>();
    where.put("directory", directory);
    where.put("path", path(directory));
    var fields = new LinkedHashMap<String, Object>();
    fields.put("code", code);
    fields.put("message", message());
    fields.put("where", where);
    fields.put("help", help());
    fields.put("exitCode", exit);
    fields.put("rendered", said);
    return fields;
  }

  private static @Nullable String path(String directory) {
    try {
      var path = java.nio.file.Path.of(directory);
      return java.nio.file.Files.isDirectory(path)
          ? path.toRealPath().toString().replace('\\', '/')
          : null;
    } catch (java.io.IOException | java.nio.file.InvalidPathException e) {
      return null;
    }
  }
}
