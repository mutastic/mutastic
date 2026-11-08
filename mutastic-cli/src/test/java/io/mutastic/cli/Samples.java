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

  /// The sample without its build output, so each test builds its own.
  static Path copy(Path from, Path to) throws IOException {
    try (Stream<Path> files = Files.walk(from)) {
      files
          .filter(
              file -> {
                var top = from.relativize(file).getName(0).toString();
                return !top.equals("build") && !top.equals(".gradle");
              })
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
