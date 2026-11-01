package io.mutastic.cli;

import io.mutastic.engine.Comparison;
import io.mutastic.engine.Engine;
import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Report;
import io.mutastic.engine.RunRecord;
import io.mutastic.engine.Status;
import io.mutastic.report.AgentText;
import io.mutastic.report.JsonEvents;
import io.mutastic.report.SourceView;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/// `mutastic`: mutation testing for a Gradle project.
///
/// ```
/// mutastic run [dir] [:module] [--format=agent|jsonl] [--threshold=N] [--only=ID] [--confirm-catches]
/// mutastic verify ID [dir] [:module]
/// ```
///
/// Exit codes: 0 when the score reaches the threshold (for `verify`, when the mutant is caught),
/// 1 when it doesn't, 2 when mutastic couldn't run.
///
/// A full run keeps its results in `build/mutastic/last-run.tsv`, and the next full run compares
/// against them: with the code unchanged, a mutant that changed result names a flaky test.
public final class Main {
  static final int PASSED = 0;
  static final int FAILED = 1;
  static final int ERROR = 2;
  private static final int DEFAULT_THRESHOLD = 80;

  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
    try {
      var command = Command.parse(args);
      var directory = command.directory().toAbsolutePath().normalize();
      var work = directory.resolve("build/mutastic");
      var project = GradleSetup.read(directory, command.module(), work);
      var sources = new SourceView(directory, project.sources());
      var engine = new Engine(workerJar(), work.resolve("worker.log"));
      var options = new Engine.Options(command.only(), command.confirmCatches());
      if (command.verify()) {
        var report = engine.run(project, options, result -> {});
        var result = report.results().getFirst();
        out.printf("mutastic: %s %s%n", result.mutant().id(), result.status().detected()
            ? "is caught by " + result.caughtBy().orElse("a timeout")
            : result.status() == Status.MISSED ? "is still missed" : "isn't reached by any test");
        return result.status().detected() ? PASSED : FAILED;
      }
      Consumer<MutantResult> progress = result -> {};
      JsonEvents events = null;
      if (command.format().equals("jsonl")) {
        events = new JsonEvents(out, sources, command.threshold());
        events.runStarted(project.name());
        progress = events::mutantFinished;
      }
      Report report = engine.run(project, options, progress);
      // Only full runs compare and are kept: one mutant says nothing about the rest.
      Optional<Comparison> comparison = Optional.empty();
      if (command.only().isEmpty()) {
        var lastRun = work.resolve("last-run.tsv");
        var record = RunRecord.of(project, report);
        comparison = RunRecord.read(lastRun).map(record::against);
        record.write(lastRun);
      }
      if (events != null) {
        events.runFinished(report, comparison);
      } else {
        out.print(new AgentText(sources, command.threshold()).render(report, comparison));
      }
      return report.score() >= command.threshold() ? PASSED : FAILED;
    } catch (UsageException e) {
      err.println("mutastic: " + e.getMessage());
      err.println("usage: mutastic run [dir] [:module] [--format=agent|jsonl] [--threshold=N] [--only=ID] [--confirm-catches]");
      err.println("       mutastic verify ID [dir] [:module]");
      return ERROR;
    } catch (RuntimeException e) {
      err.println("mutastic: " + e.getMessage());
      return ERROR;
    }
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

  record Command(boolean verify, Path directory, String module, String format, int threshold, Optional<String> only,
      boolean confirmCatches) {
    static Command parse(String[] args) {
      if (args.length == 0 || !List.of("run", "verify").contains(args[0])) {
        throw new UsageException(args.length == 0 ? "no command" : "unknown command " + args[0]);
      }
      boolean verify = args[0].equals("verify");
      var positional = new ArrayList<String>();
      String format = "agent";
      int threshold = DEFAULT_THRESHOLD;
      Optional<String> only = Optional.empty();
      boolean confirmCatches = false;
      for (String arg : List.of(args).subList(1, args.length)) {
        if (arg.startsWith("--format=")) {
          format = arg.substring("--format=".length());
          if (!List.of("agent", "jsonl").contains(format)) {
            throw new UsageException("unknown format " + format);
          }
        } else if (arg.startsWith("--threshold=")) {
          threshold = Integer.parseInt(arg.substring("--threshold=".length()));
        } else if (arg.equals("--confirm-catches")) {
          confirmCatches = true;
        } else if (arg.startsWith("--only=")) {
          only = Optional.of(arg.substring("--only=".length()));
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
      return new Command(verify, directory, module, format, threshold, only, confirmCatches);
    }
  }
}
