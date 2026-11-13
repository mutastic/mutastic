package io.mutastic.cli;

import io.mutastic.api.BuildTool;
import io.mutastic.engine.Engine;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// A command that couldn't do what it was asked, as stderr says it and the exit status it ends
/// with: what went wrong, then the command to run instead, the command's usage, or a hint.
///
/// @param said every line, each ending in a newline
record Failure(int exit, String said) {
  /// What `failure`, thrown running the command line `args`, says, and its exit status.
  static Failure of(RuntimeException failure, List<String> args, Map<String, String> env) {
    var headline = "mutastic: " + failure.getMessage() + "\n";
    return switch (failure) {
      case Main.UsageException usage ->
          new Failure(
              Main.USAGE,
              headline + corrected(usage.correction(), args).orElseGet(() -> Help.usage(args)));
      case Main.ConfigException config -> new Failure(Main.USAGE, headline);
      case Engine.UnknownMutant unknown ->
          new Failure(
              Main.USAGE,
              headline
                  + corrected(unknown.closest().map(id -> Correction.of(unknown.id(), id)), args)
                      .orElse(""));
      case BuildTool.SetupFailed setup -> withHint(headline, setup, env);
      case Engine.CantRun cantRun -> withHint(headline, cantRun, env);
      case Shares.Refused refused -> withHint(headline, refused, env);
      case Engine.BaselineFailed red -> new Failure(Main.TESTS_FAIL, headline);
      case java.io.UncheckedIOException disk ->
          withHint("mutastic: " + Disk.said(disk) + "\n", disk, env);
      default -> new Failure(Main.INTERNAL, "mutastic: internal error: " + failure + "\n");
    };
  }

  /// A usage error's status, and the Codex sandbox's hint when the sandbox is why.
  private static Failure withHint(
      String headline, RuntimeException failure, Map<String, String> env) {
    return new Failure(
        Main.USAGE, headline + CodexSandbox.hint(failure, env).map(hint -> hint + "\n").orElse(""));
  }

  /// The command line with the correction made, to copy: none when a build plugin passed the
  /// arguments, which the person wrote in its own form.
  private static Optional<String> corrected(Optional<Correction> correction, List<String> args) {
    if (args.stream().anyMatch(arg -> arg.startsWith("--setup"))) {
      return Optional.empty();
    }
    return correction.flatMap(each -> each.in(args)).map(fixed -> "  mutastic " + fixed + "\n");
  }
}
