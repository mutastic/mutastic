package io.mutastic.cli;

import io.mutastic.api.BuildArguments;
import io.mutastic.api.BuildTool;
import io.mutastic.engine.Engine;
import io.mutastic.output.Correction;
import io.mutastic.output.Remedies;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
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
          AtMutants.NONE,
          AtFiles.OUTSIDE_SOURCES,
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
  static Failure of(RuntimeException failure, List<String> typed, Map<String, String> env) {
    var own = of(failure, env, BuildArgs.own(typed));
    var ending = BuildArgs.typed(typed).map(BuildArguments::ending).orElse("");
    return ending.isEmpty()
        ? own
        : new Failure(own.exit(), own.code(), withEnding(own.said(), ending));
  }

  /// A command that takes the build tool's arguments, on a line of its own, to copy.
  private static final Pattern ON_ITS_LINE =
      Pattern.compile(
          "(?m)^  mutastic (?:run|verify|explain|ignore|next|baseline|gate|mutants)\\b"
              + "(?!.* --help$).*$");

  /// Each command offered, ending in the build tool's arguments as they were typed.
  static String withEnding(String said, String ending) {
    return ON_ITS_LINE
        .matcher(said)
        .replaceAll(line -> Matcher.quoteReplacement(ended(line.group(), ending)));
  }

  /// Once: some are offered with it already, or with it and an argument more after it.
  private static String ended(String command, String ending) {
    return command.endsWith(ending) || command.contains(ending + " ") ? command : command + ending;
  }

  private static Failure of(RuntimeException failure, Map<String, String> env, List<String> args) {
    var message = String.valueOf(failure.getMessage());
    var headline = "mutastic: " + message + "\n";
    return switch (failure) {
      case Main.UsageException usage -> usage(usage, headline, args);
      case Main.ConfigException config -> new Failure(Main.USAGE, config.code(), headline);
      case Engine.UnknownMutant unknown ->
          new Failure(Main.USAGE, "unknown-id", headline + closeId(unknown, args));
      case BuildTool.SetupFailed setup -> setup(setup, message, args, env);
      case Engine.NoTestsRan none -> withHint("no-tests", message, none, env);
      case Engine.TestsJdkTooOld old -> withHint("build-jdk", message, old, env);
      case Engine.NothingCovered nothing -> withHint("nothing-covered", message, nothing, env);
      case Engine.CantRun cantRun -> withHint(OTHER, message, cantRun, env);
      case Shares.Refused refused -> withHint("shards", message, refused, env);
      case Engine.BaselineFailed red -> new Failure(Main.TESTS_FAIL, red.code(), headline);
      case java.io.UncheckedIOException disk -> withHint("disk", Disk.said(disk), disk, env);
      default ->
          new Failure(Main.INTERNAL, "internal", "mutastic: internal error: " + failure + "\n");
    };
  }

  /// A build that couldn't be set up: a directory that isn't there, said as the Rust edition says
  /// it; else the message, and the command again on the project's directory when it's in one.
  private static Failure setup(
      BuildTool.SetupFailed setup, String message, List<String> args, Map<String, String> env) {
    if (setup.code().equals("no-directory")) {
      return withHint("no-directory", missingDirectory(message, args), setup, env);
    }
    return withHint(
        setup.code().equals("setup") ? OTHER : setup.code(),
        message + elsewhere(setup, message, args),
        setup,
        env);
  }

  /// A mistake in the command line: the command corrected, when it's a close name; else the message
  /// ends in what to run, if anything.
  private static Failure usage(Main.UsageException usage, String headline, List<String> args) {
    return new Failure(
        Main.USAGE, usage.code(), headline + corrected(usage.correction(), args).orElse(""));
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

  /// A directory that isn't there, as the Rust edition says it: as typed, and where it was looked
  /// for when it was typed relative; then a directory beside it a slip away, with the command again
  /// on it.
  private static String missingDirectory(String message, List<String> args) {
    var lead = "there's no directory ";
    if (!message.startsWith(lead)) {
      return message;
    }
    var missing = java.nio.file.Path.of(message.substring(lead.length()));
    var given =
        args.stream()
            .filter(arg -> !arg.startsWith("-") && !named(arg, args))
            .filter(arg -> java.nio.file.Path.of(arg).toAbsolutePath().normalize().equals(missing))
            .findFirst();
    if (given.isEmpty()) {
      return message;
    }
    var typed = given.get();
    var headline =
        java.nio.file.Path.of(typed).isAbsolute()
            ? "there's no directory %s.".formatted(typed)
            : "there's no directory %s in %s."
                .formatted(typed, java.nio.file.Path.of("").toAbsolutePath());
    return beside(missing, typed, args).map(line -> headline + "\n" + line).orElse(headline);
  }

  /// A directory beside `missing` a slip away from its name, and the command again on it, if
  /// there's one.
  private static Optional<String> beside(
      java.nio.file.Path missing, String typed, List<String> args) {
    var name = missing.getFileName();
    var parent = missing.getParent();
    if (name == null || parent == null || !java.nio.file.Files.isDirectory(parent)) {
      return Optional.empty();
    }
    return io.mutastic.api.Nearest.name(name.toString(), directories(parent))
        .flatMap(
            close -> {
              var instead = typed.substring(0, typed.length() - name.toString().length()) + close;
              return Correction.of(typed, instead)
                  .in(args)
                  .map(
                      line ->
                          "There's a directory called %s. To test it:\n  mutastic %s"
                              .formatted(close, line));
            });
  }

  /// The names of the directories in `parent`, in order; none if it can't be listed.
  private static List<String> directories(java.nio.file.Path parent) {
    try (var listed = java.nio.file.Files.list(parent)) {
      return listed
          .filter(java.nio.file.Files::isDirectory)
          .map(dir -> String.valueOf(dir.getFileName()))
          .sorted()
          .toList();
    } catch (java.io.IOException e) {
      return List.of();
    }
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

  /// The command line with the correction made, to copy: none when a build plugin passed the
  /// arguments, which the person wrote in its own form.
  private static Optional<String> corrected(Optional<Correction> correction, List<String> args) {
    if (args.stream().anyMatch(arg -> arg.startsWith("--setup"))) {
      return Optional.empty();
    }
    return correction.flatMap(each -> each.in(args)).map(fixed -> "  mutastic " + fixed + "\n");
  }

  /// How a line that is a command starts: mutastic's, a build tool's (a wrapper that isn't
  /// executable after `sh`, its path in quotes when it has a space), or the look at a log.
  private static final Pattern COMMAND =
      Pattern.compile(
          "^  (?:mutastic |(?:sh )?(?:\\S*|'[^']*|\"[^\"]*)"
              + "(?:gradlew(?:\\.bat)?|mvnw(?:\\.cmd)?)['\"]? |gradle |mvn |tail )");

  /// What went wrong, as the first line says it, without `mutastic: `.
  String message() {
    return said.lines().findFirst().orElse("").replaceFirst("^mutastic: ", "");
  }

  /// The commands the message ends in, each on a line of its own, set in by two spaces, with how
  /// sure mutastic is of each: [Remedies].
  List<Map<String, Object>> help() {
    return Remedies.in(said, COMMAND);
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
