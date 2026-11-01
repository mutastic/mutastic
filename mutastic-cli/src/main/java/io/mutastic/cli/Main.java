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
import io.mutastic.report.ReportFiles;
import io.mutastic.report.SourceView;
import io.mutastic.report.Style;
import io.mutastic.report.Vocabulary;
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
/// mutastic run [dir] [:module] [--format=full|brief|concise|grouped|agent|jsonl] [--threshold=N] [--only=ID]
///              [--confirm-catches] [--workers=N] [--color=auto|always|never]
///              [--hyperlinks=auto|always|never] [--progress=auto|bar|counter|none] [--no-suggestions]
///              [--report=json|html|xml|none[:path]]... [--vocabulary=plain|classic]
/// mutastic verify ID [dir] [:module]
/// ```
///
/// The result goes to stdout, progress to stderr. The format is `--format`, else
/// `MUTASTIC_FORMAT`, else `agent` when `MUTASTIC_AGENT=1`, else `full` on a terminal and
/// `concise` elsewhere. `MUTASTIC_VOCABULARY=classic` says killed and survived;
/// `MUTASTIC_PILLS=rounded` draws round label ends for a Nerd Font. A full run also writes report files, by
/// default `build/mutastic/mutation.json` and `mutation.html`.
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
  private static final List<String> FORMATS = List.of("full", "brief", "concise", "grouped", "agent", "jsonl");
  /// The formats for people: they get each file's line as it finishes.
  private static final List<String> PEOPLE = List.of("full", "brief", "concise", "grouped");
  private static final List<String> VOCABULARIES = List.of("plain", "classic");

  private static HumanText.Detail detail(String format) {
    return switch (format) {
      case "brief" -> HumanText.Detail.BRIEF;
      case "concise" -> HumanText.Detail.CONCISE;
      case "grouped" -> HumanText.Detail.GROUPED;
      default -> HumanText.Detail.FULL;
    };
  }

  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err, Terminal.current()));
  }

  static int run(String[] args, PrintStream out, PrintStream err, Terminal terminal) {
    try {
      var command = Command.parse(args);
      var format = command.format().orElse(terminal.defaultFormat());
      if (!FORMATS.contains(format)) {
        throw new UsageException("unknown format %s in MUTASTIC_FORMAT (one of %s)".formatted(format, String.join(", ", FORMATS)));
      }
      var vocabulary = command.vocabulary().orElse(terminal.defaultVocabulary());
      if (!VOCABULARIES.contains(vocabulary)) {
        throw new UsageException("unknown vocabulary %s in MUTASTIC_VOCABULARY (one of plain, classic)".formatted(vocabulary));
      }
      var words = Vocabulary.valueOf(vocabulary.toUpperCase(java.util.Locale.ROOT));
      var progress = new Progress(err, progressMode(command.progress(), format, terminal));
      progress.started(version());
      long start = System.nanoTime();
      var startedAt = java.time.LocalTime.now();
      var directory = command.directory().toAbsolutePath().normalize();
      var work = workDirectory(directory, command.module());
      var here = Path.of("").toAbsolutePath().normalize();
      var target = target(here, directory, command.module());
      var project = GradleSetup.read(directory, command.module(), work, String.join(" ", target(here, directory, ":")));
      var setup = Duration.ofNanos(System.nanoTime() - start);
      var sources = new SourceView(directory, project.sources(), project.testSources()).withTarget(target);
      var style = terminal.colour(command.colour()) ? Style.COLOUR : Style.PLAIN;
      style = terminal.links(command.links()) ? style.withLinks() : style;
      style = terminal.roundedLabels() ? style.withRoundedLabels() : style;
      var human = new HumanText(sources, command.threshold(), style, detail(format), words);
      if (PEOPLE.contains(format)) {
        progress.showFiles(results -> human.fileFinished(project.name(), results));
      }
      var engine = new Engine(workerJar(), work.resolve("worker.log"));
      var options = new Engine.Options(command.only(), command.confirmCatches(),
          command.workers().orElse(Engine.defaultWorkers()), command.suggestions() && !command.verify());
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

          @Override
          public void fileFinished(String topLevelClass, List<MutantResult> results) {
            progress.fileFinished(topLevelClass, results);
          }
        };
      }
      Report report = engine.run(project, options, listener);
      progress.finished();
      // Only full runs compare, are kept and write reports: one mutant says nothing about the rest.
      Optional<Comparison> comparison = Optional.empty();
      var written = new ArrayList<String>();
      if (command.only().isEmpty()) {
        var lastRun = work.resolve("last-run.tsv");
        var record = RunRecord.of(project, RunRecord.toolOf(List.of(codeSource(Engine.class), workerJar())), report);
        comparison = RunRecord.read(lastRun).map(record::against);
        record.write(lastRun);
        var files = new ReportFiles(directory, sources, command.threshold(), version());
        for (var requested : command.reports()) {
          var file = requested.path().map(directory::resolve).orElse(work.resolve(defaultName(requested.kind())));
          files.write(requested.kind(), report, file);
          written.add(file.startsWith(directory) ? directory.relativize(file).toString() : file.toString());
        }
      }
      switch (format) {
        case "jsonl" -> events.runFinished(report, comparison, written, command.only().isPresent());
        case "agent" -> out.print(new AgentText(sources, command.threshold(), words)
            .render(report, comparison, command.only().isPresent()));
        default -> out.print(human.render(report, comparison,
            new HumanText.Run(startedAt, setup, written, command.only().isPresent())));
      }
      // One mutant asked for by id passes when it's caught, as `verify` does; a whole run, on its score.
      if (command.only().isPresent()) {
        return report.results().stream().allMatch(result -> result.status().detected()) ? PASSED : FAILED;
      }
      return report.score() >= command.threshold() ? PASSED : FAILED;
    } catch (UsageException e) {
      err.println("mutastic: " + e.getMessage());
      err.println("usage: mutastic run [dir] [:module] [--format=full|brief|concise|grouped|agent|jsonl] [--threshold=N]");
      err.println("                    [--only=ID] [--vocabulary=plain|classic]");
      err.println("                    [--confirm-catches] [--workers=N] [--color=auto|always|never]");
      err.println("                    [--hyperlinks=auto|always|never] [--progress=auto|bar|counter|none]");
      err.println("                    [--no-suggestions] [--report=json|html|xml|none[:path]]...");
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

  /// `build/mutastic` for the root project; `build/mutastic/data` for `:data`, so each module
  /// keeps its own run record, logs and reports.
  static Path workDirectory(Path directory, String module) {
    var work = directory.resolve("build/mutastic");
    return module.equals(":") ? work : work.resolve(module.substring(1).replace(':', '/'));
  }

  /// Where a report goes unless told: beside the run record.
  private static String defaultName(String kind) {
    return switch (kind) {
      case "json" -> "mutation.json";
      case "html" -> "mutation.html";
      default -> "mutations.xml";
    };
  }

  /// The project as it would be typed from `here`: `samples/pricing :api`, a path relative to
  /// `here` when it's inside it, else absolute; nothing for `here` and its root project.
  static List<String> target(Path here, Path directory, String module) {
    var words = new ArrayList<String>();
    if (!directory.equals(here)) {
      words.add(directory.startsWith(here) ? here.relativize(directory).toString() : directory.toString());
    }
    if (!module.equals(":")) {
      words.add(module);
    }
    return words;
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

  private static Path codeSource(Class<?> type) {
    try {
      return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
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

  /// The commands, and whether each works on one mutant. Every one that does must be offered
  /// for each mutant in the output (`MutantCommand`), and its tests check that.
  enum Subcommand {
    RUN(false),
    VERIFY(true);

    final boolean takesMutantId;

    Subcommand(boolean takesMutantId) {
      this.takesMutantId = takesMutantId;
    }

    String word() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }

    static Optional<Subcommand> of(String word) {
      return java.util.Arrays.stream(values()).filter(command -> command.word().equals(word)).findFirst();
    }
  }

  static final class UsageException extends RuntimeException {
    UsageException(String message) {
      super(message);
    }
  }

  /// A report file to write: `json`, `html` or `xml`, where the default isn't wanted.
  record RequestedReport(String kind, Optional<Path> path) {}

  /// `json` and `html`, unless `--report` says otherwise.
  static final List<RequestedReport> DEFAULT_REPORTS =
      List.of(new RequestedReport("json", Optional.empty()), new RequestedReport("html", Optional.empty()));

  /// @param format empty for the default, which depends on where output goes
  /// @param workers empty for the default, which depends on the machine
  /// @param reports the report files to write after a full run
  record Command(boolean verify, Path directory, String module, Optional<String> format, int threshold,
      Optional<String> only, boolean confirmCatches, Optional<Integer> workers, String colour, String links,
      String progress, boolean suggestions, List<RequestedReport> reports, Optional<String> vocabulary) {
    static Command parse(String[] args) {
      if (args.length == 0 || Subcommand.of(args[0]).isEmpty()) {
        throw new UsageException(args.length == 0 ? "no command" : "unknown command " + args[0]);
      }
      var subcommand = Subcommand.of(args[0]).orElseThrow();
      boolean verify = subcommand == Subcommand.VERIFY;
      var positional = new ArrayList<String>();
      Optional<String> format = Optional.empty();
      int threshold = DEFAULT_THRESHOLD;
      Optional<String> only = Optional.empty();
      boolean confirmCatches = false;
      Optional<Integer> workers = Optional.empty();
      String colour = "auto";
      String links = "auto";
      String progress = "auto";
      boolean suggestions = true;
      List<RequestedReport> reports = null;
      Optional<String> vocabulary = Optional.empty();
      for (String arg : List.of(args).subList(1, args.length)) {
        if (arg.startsWith("--format=")) {
          format = Optional.of(choice(arg, FORMATS));
        } else if (arg.startsWith("--threshold=")) {
          threshold = number(arg);
        } else if (arg.startsWith("--report=")) {
          var value = arg.substring("--report=".length());
          int colon = value.indexOf(':');
          var kind = colon < 0 ? value : value.substring(0, colon);
          if (!kind.equals("none") && !ReportFiles.KINDS.contains(kind)) {
            throw new UsageException("unknown report %s (one of json, html, xml, none)".formatted(kind));
          }
          if (reports == null) {
            reports = new ArrayList<>(); // the first --report replaces the defaults
          }
          if (!kind.equals("none")) {
            reports.add(new RequestedReport(kind, colon < 0 ? Optional.empty() : Optional.of(Path.of(value.substring(colon + 1)))));
          }
        } else if (arg.startsWith("--vocabulary=")) {
          vocabulary = Optional.of(choice(arg, VOCABULARIES));
        } else if (arg.equals("--no-suggestions")) {
          suggestions = false;
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
          progress, suggestions, reports == null ? DEFAULT_REPORTS : List.copyOf(reports), vocabulary);
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
