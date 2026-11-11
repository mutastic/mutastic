package io.mutastic.cli;

import io.mutastic.report.Schemas;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/// Running mutastic on copies of the sample projects.
final class Samples {
  private Samples() {}

  record Result(int exit, String out, String err) {}

  static Result mutastic(String... args) {
    return mutastic(java.util.Map.of(), args);
  }

  /// With `env` as the environment: `MUTASTIC_ARID=audit`, say. JSON Lines output is checked
  /// against the published events schema, so every event any test's run writes is one it describes,
  /// field for field.
  static Result mutastic(java.util.Map<String, String> env, String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int exit =
        Main.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8),
            new Terminal(false, env));
    var result =
        new Result(
            exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    if (jsonl(env, List.of(args))) {
      Schemas.checkEvents(result.out());
    }
    return result;
  }

  /// Whether the run writes JSON Lines: `--format=jsonl`, `--format jsonl`, or the environment's.
  private static boolean jsonl(java.util.Map<String, String> env, List<String> args) {
    int format = args.indexOf("--format");
    return args.contains("--format=jsonl")
        || (format >= 0 && format + 1 < args.size() && args.get(format + 1).equals("jsonl"))
        || (args.stream().noneMatch(arg -> arg.startsWith("--format"))
            && "jsonl".equals(env.get("MUTASTIC_FORMAT")));
  }

  /// A module's build output as well as the whole sample's, so a sample copied from a machine where
  /// it has been built is the same as one copied from a fresh checkout. Only the top level used to
  /// be skipped, which left a multi-module sample's `app/build` in the copy: the tests that read a
  /// module's output then saw a previous run's, and `GradlePluginTest` found test results it had
  /// asserted could not be there.
  private static boolean isOutput(Path within) {
    for (Path name : within) {
      if (OUTPUT.contains(name.toString())) {
        return true;
      }
    }
    return false;
  }

  /// What a build writes: Gradle's `build` and `.gradle`, Maven's `target`.
  private static final Set<String> OUTPUT = Set.of("build", ".gradle", "target");

  /// The sample without its build output, so each test builds its own.
  static Path copy(Path from, Path to) throws IOException {
    try (Stream<Path> files = Files.walk(from)) {
      files
          .filter(file -> !isOutput(from.relativize(file)))
          .forEach(
              file -> {
                try {
                  var target = to.resolve(from.relativize(file).toString());
                  if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                  } else {
                    Files.copy(file, target);
                    target.toFile().setExecutable(file.toFile().canExecute());
                  }
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    }
    return to;
  }
}
