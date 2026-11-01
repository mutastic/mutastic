package io.mutastic.cli;

import io.mutastic.engine.Comparison;
import io.mutastic.engine.Engine;
import io.mutastic.engine.Listener;
import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Report;
import io.mutastic.engine.RunRecord;
import io.mutastic.engine.Status;
import io.mutastic.report.AgentText;
import io.mutastic.report.HumanText;
import io.mutastic.report.JsonEvents;
import io.mutastic.report.SourceView;
import io.mutastic.report.Style;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// `mutastic`: mutation testing for a Gradle project.
///
/// ```
/// mutastic run [dir] [:module] [--format=full|concise|agent|jsonl] [--threshold=N] [--only=ID]
///              [--confirm-catches] [--workers=N] [--color=auto|always|never]
///              [--hyperlinks=auto|always|never] [--progress=auto|bar|counter|none]
/// mutastic verify ID [dir] [:module]
/// ```
///
/// The result goes to stdout, progress to stderr. The format is `full` on a terminal, `concise`
/// elsewhere, and `agent` when `MUTASTIC_AGENT=1`.
///
/// A full run keeps its results in `build/mutastic/last-run.tsv`, and the next full run compares
/// against them: with the code unchanged, a mutant that changed result names a flaky test.
public final class Main {
  /// The score reaches the threshold; for `verify`, the mutant is caught.
  static final int PASSED = 0;
  /// The score is below the threshold; for `verify`, the mutant isn't caught.
  static final int FAILED = 1;
  /// A usage error, or a build mutastic couldn't read.
  static final int USAGE = 2;
  /// The tests fail without any mutant.
  static final int TESTS_FAIL = 3;
  /// mutastic itself went wrong.
  static final int INTERNAL = 4;

  private static final int DEFAULT_THRESHOLD = 80;
  private static final List<String> FORMATS = List.of("full", "concise", "agent", "jsonl");

  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err, Terminal.current()));
  }

  static int run(String[] args, PrintStream out, PrintStream err, Terminal terminal) {
    try {
      var command = Command.parse(args);
      var format = command.format().orElse(terminal.defaultFormat());
      var progress = new Progress(err, progressMode(command.progress(), format, terminal));
      progress.started(version());
      long start = System.nanoTime();
      var directory = command.directory().toAbsolutePath().normalize();
      var work = directory.resolve("build/mutastic");
      var project = GradleSetup.read(directory, command.module(), work);
      var setup = Duration.ofNanos(System.nanoTime() - start);
      var sources = new SourceView(directory, project.sources());
      var engine = new Engine(workerJar(), work.resolve("worker.log"));
      var options = new Engine.Options(command.only(), command.confirmCatches(),
          command.workers().orElse(Engine.defaultWorkers()));
      if (command.verify()) {
        var report = engine.run(project, options, progress);
        progress.finished();
        var result = report.results().getFirst();
        out.printf("mutastic: %s %s%n", result.mutant().id(), result.status().detected()
            ? "is caught by " + result.caughtBy().orElse("a timeout")
            : result.status() == Status.MISSED ? "is still missed" : "isn't reached by any test");
        return result.status().detected() ? PASSED : FAILED;
      }
      JsonEvents events = format.equals("jsonl") ? new JsonEvents(out, sources, command.threshold()) : null;
      Listener listener = progress;
      if (events != null) {
        events.runStarted(project.name());
        listener = new Listener() {
          @Override
          public void coverageFinished(int jdk, int tests, Duration took, int mutants, List<String> flaky) {
            progress.coverageFinished(jdk, tests, took, mutants, flaky);
          }

          @Override
          public void mutantFinished(MutantResult result) {
            progress.mutantFinished(result);
            events.mutantFinished(result);
          }
        };
      }
      Report report = engine.run(project, options, listener);
      progress.finished();
      // Only full runs compare and are kept: one mutant says nothing about the rest.
      Optional<Comparison> comparison = Optional.empty();
      if (command.only().isEmpty()) {
        var lastRun = work.resolve("last-run.tsv");
        var record = RunRecord.of(project, report);
        comparison = RunRecord.read(lastRun).map(record::against);
        record.write(lastRun);
      }
      switch (format) {
        case "jsonl" -> events.runFinished(report, comparison);
        case "agent" -> out.print(new AgentText(sources, command.threshold()).render(report, comparison));
        default -> {
          var style = terminal.colour(command.colour()) ? Style.COLOUR : Style.PLAIN;
          out.print(new HumanText(sources, command.threshold(), terminal.links(command.links()) ? style.withLinks() : style,
              format.equals("concise")).render(report, comparison, setup));
        }
      }
      return report.score() >= command.threshold() ? PASSED : FAILED;
    } catch (UsageException e) {
      err.println("mutastic: " + e.getMessage());
      err.println("usage: mutastic run [dir] [:module] [--format=full|concise|agent|jsonl] [--threshold=N] [--only=ID]");
      err.println("                    [--confirm-catches] [--workers=N] [--color=auto|always|never]");
      err.println("                    [--hyperlinks=auto|always|never] [--progress=auto|bar|counter|none]");
      err.println("       mutastic verify ID [dir] [:module]");
      return USAGE;
    } catch (GradleSetup.SetupFailed | IllegalArgumentException e) {
      err.println("mutastic: " + e.getMessage());
      return USAGE;
    } catch (Engine.BaselineFailed e) {
      err.println("mutastic: " + e.getMessage());
      return TESTS_FAIL;
    } catch (RuntimeException e) {
      err.println("mutastic: internal error: " + e);
      return INTERNAL;
    }
  }

  /// A bar on a terminal for people; counter lines in logs; nothing for agents and JSONL
  /// readers, who only want the result.
  private static Progress.Mode progressMode(String choice, String format, Terminal terminal) {
    return switch (choice) {
      case "bar" -> Progress.Mode.BAR;
      case "counter" -> Progress.Mode.COUNTER;
      case "none" -> Progress.Mode.NONE;
      default -> format.equals("agent") || format.equals("jsonl") ? Progress.Mode.NONE
          : terminal.interactive() ? Progress.Mode.BAR : Progress.Mode.COUNTER;
    };
  }

  private static String version() {
    var version = Main.class.getPackage().getImplementationVersion();
    return version == null ? "dev" : version;
  }

  /// The worker jar ships on mutastic's own classpath; the engine starts it in its own process.
  private static Path workerJar() {
    try {
      var worker = Class.forName("io.mutastic.worker.Worker", false, Main.class.getClassLoader());
      return Path.of(worker.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (ClassNotFoundException | URISyntaxException e) {
      throw new IllegalStateException("mutastic's test worker is missing from its installation", e);
    }
  }

  static final class UsageException extends RuntimeException {
    UsageException(String message) {
      super(message);
    }
  }

  /// @param format empty for the default, which depends on where output goes
  /// @param workers empty for the default, which depends on the machine
  record Command(boolean verify, Path directory, String module, Optional<String> format, int threshold,
      Optional<String> only, boolean confirmCatches, Optional<Integer> workers, String colour, String links,
      String progress) {
    static Command parse(String[] args) {
      if (args.length == 0 || !List.of("run", "verify").contains(args[0])) {
        throw new UsageException(args.length == 0 ? "no command" : "unknown command " + args[0]);
      }
      boolean verify = args[0].equals("verify");
      var positional = new ArrayList<String>();
      Optional<String> format = Optional.empty();
      int threshold = DEFAULT_THRESHOLD;
      Optional<String> only = Optional.empty();
      boolean confirmCatches = false;
      Optional<Integer> workers = Optional.empty();
      String colour = "auto";
      String links = "auto";
      String progress = "auto";
      for (String arg : List.of(args).subList(1, args.length)) {
        if (arg.startsWith("--format=")) {
          format = Optional.of(choice(arg, FORMATS));
        } else if (arg.startsWith("--threshold=")) {
          threshold = number(arg);
        } else if (arg.equals("--confirm-catches")) {
          confirmCatches = true;
        } else if (arg.startsWith("--only=")) {
          only = Optional.of(arg.substring("--only=".length()));
        } else if (arg.startsWith("--workers=")) {
          workers = Optional.of(number(arg));
          if (workers.get() < 1) {
            throw new UsageException("--workers must be at least 1");
          }
        } else if (arg.startsWith("--color=")) {
          colour = choice(arg, List.of("auto", "always", "never"));
        } else if (arg.startsWith("--hyperlinks=")) {
          links = choice(arg, List.of("auto", "always", "never"));
        } else if (arg.startsWith("--progress=")) {
          progress = choice(arg, List.of("auto", "bar", "counter", "none"));
        } else if (arg.startsWith("--")) {
          throw new UsageException("unknown option " + arg);
        } else {
          positional.add(arg);
        }
      }
      if (verify) {
        if (positional.isEmpty()) {
          throw new UsageException("verify needs a mutant id");
        }
        only = Optional.of(positional.removeFirst());
      }
      var module = positional.stream().filter(arg -> arg.startsWith(":")).findFirst().orElse(":");
      var directory = positional.stream().filter(arg -> !arg.startsWith(":")).findFirst().map(Path::of).orElse(Path.of("."));
      return new Command(verify, directory, module, format, threshold, only, confirmCatches, workers, colour, links,
          progress);
    }

    /// The whole number in `--name=value`.
    private static int number(String arg) {
      var value = arg.substring(arg.indexOf('=') + 1);
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException e) {
        throw new UsageException("%s needs a whole number, not %s".formatted(arg.substring(0, arg.indexOf('=')), value));
      }
    }

    /// The value of `--name=value`, if it's one of `allowed`.
    private static String choice(String arg, List<String> allowed) {
      var name = arg.substring(2, arg.indexOf('='));
      var value = arg.substring(arg.indexOf('=') + 1);
      if (!allowed.contains(value)) {
        throw new UsageException("unknown %s %s (one of %s)".formatted(name, value, String.join(", ", allowed)));
      }
      return value;
    }
  }
}
