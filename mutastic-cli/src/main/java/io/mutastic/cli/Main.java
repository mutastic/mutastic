package io.mutastic.cli;

import io.mutastic.engine.Comparison;
import io.mutastic.engine.CoveragePass;
import io.mutastic.engine.Engine;
import io.mutastic.engine.Listener;
import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Project;
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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
/// The result goes to stdout, progress to stderr. The format is `--format`, else `MUTASTIC_FORMAT`,
/// else `agent` when `MUTASTIC_AGENT=1`, else `full` on a terminal and `concise` elsewhere.
/// `MUTASTIC_VOCABULARY=classic` says killed and survived; `MUTASTIC_PILLS=rounded` draws round
/// label ends for a Nerd Font. A full run also writes report files, by default
/// `build/mutastic/mutation.json` and `mutation.html`.
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
  private static final List<String> FORMATS =
      List.of("full", "brief", "concise", "grouped", "agent", "jsonl");

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
      return run(Command.parse(args), out, err, terminal);
    } catch (UsageException e) {
      err.println("mutastic: " + e.getMessage());
      printUsage(err);
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

  private static int run(Command command, PrintStream out, PrintStream err, Terminal terminal) {
    var output = output(command, out, err, terminal);
    var progress = output.progress();
    progress.started(version());
    var prepared = prepare(command);
    var human = humanText(command, output, prepared.sources());
    if (PEOPLE.contains(output.format())) {
      progress.showFiles(results -> human.fileFinished(prepared.project().name(), results));
    }
    var engine = new Engine(workerJar(), prepared.work().resolve("worker.log"));
    var options = options(command);
    if (command.verify()) {
      var report = engine.run(prepared.project(), options, progress);
      progress.finished();
      return verified(report.results().getFirst(), out);
    }
    var display = display(command, prepared, output, human);
    Report report = engine.run(prepared.project(), options, display.listener());
    progress.finished();
    // Only full runs compare, are kept and write reports: one mutant says nothing about the rest.
    var kept = command.only().isEmpty() ? keep(command, prepared, report) : Kept.NOTHING;
    display.printer().print(report, kept.comparison(), kept.written());
    return exitCode(command, report);
  }

  private static void printUsage(PrintStream err) {
    err.println(
        "usage: mutastic run [dir] [:module] [--format=full|brief|concise|grouped|agent|jsonl] [--threshold=N]");
    err.println("                    [--only=ID] [--vocabulary=plain|classic]");
    err.println(
        "                    [--confirm-catches] [--workers=N] [--color=auto|always|never]");
    err.println(
        "                    [--hyperlinks=auto|always|never] [--progress=auto|bar|counter|none]");
    err.println("                    [--no-suggestions] [--report=json|html|xml|none[:path]]...");
    err.println("       mutastic verify ID [dir] [:module]");
  }

  /// Where and how the result is shown: stdout and progress, the format, the words for results and
  /// the terminal's style.
  private record Output(
      PrintStream out, Progress progress, String format, Vocabulary words, Style style) {}

  private static Output output(
      Command command, PrintStream out, PrintStream err, Terminal terminal) {
    var format = command.format().orElse(terminal.defaultFormat());
    if (!FORMATS.contains(format)) {
      throw new UsageException(
          "unknown format %s in MUTASTIC_FORMAT (one of %s)"
              .formatted(format, String.join(", ", FORMATS)));
    }
    var vocabulary = command.vocabulary().orElse(terminal.defaultVocabulary());
    if (!VOCABULARIES.contains(vocabulary)) {
      throw new UsageException(
          "unknown vocabulary %s in MUTASTIC_VOCABULARY (one of plain, classic)"
              .formatted(vocabulary));
    }
    var words = Vocabulary.valueOf(vocabulary.toUpperCase(java.util.Locale.ROOT));
    var progress = new Progress(err, progressMode(command.progress(), format, terminal));
    return new Output(out, progress, format, words, style(command, terminal));
  }

  private static Style style(Command command, Terminal terminal) {
    var style = terminal.colour(command.colour()) ? Style.COLOUR : Style.PLAIN;
    style = terminal.links(command.links()) ? style.withLinks() : style;
    return terminal.roundedLabels() ? style.withRoundedLabels() : style;
  }

  /// A project read through its Gradle build: where it is, where mutastic keeps its files, its code
  /// as the output shows it, and when and how long reading it took.
  private record Prepared(
      Path directory,
      Path work,
      Project project,
      SourceView sources,
      LocalTime startedAt,
      Duration setup) {}

  private static Prepared prepare(Command command) {
    long start = System.nanoTime();
    var startedAt = LocalTime.now(java.time.ZoneId.systemDefault()); // the reader's clock
    var directory = command.directory().toAbsolutePath().normalize();
    var work = workDirectory(directory, command.module());
    var here = Path.of("").toAbsolutePath().normalize();
    var target = target(here, directory, command.module());
    var project =
        GradleSetup.read(
            directory, command.module(), work, String.join(" ", target(here, directory, ":")));
    var setup = Duration.ofNanos(System.nanoTime() - start);
    var sources =
        new SourceView(directory, project.sources(), project.testSources()).withTarget(target);
    return new Prepared(directory, work, project, sources, startedAt, setup);
  }

  private static HumanText humanText(Command command, Output output, SourceView sources) {
    return new HumanText(
        sources, command.threshold(), output.style(), detail(output.format()), output.words());
  }

  private static Engine.Options options(Command command) {
    return new Engine.Options(
        command.only(),
        command.confirmCatches(),
        command.workers().orElse(Engine.defaultWorkers()),
        command.suggestions() && !command.verify());
  }

  /// What `verify` says of its mutant, and whether it passed.
  private static int verified(MutantResult result, PrintStream out) {
    out.printf(
        "mutastic: %s %s%n",
        result.mutant().id(),
        result.status().detected()
            ? "is caught by " + result.caughtBy().orElse("a timeout")
            : result.status() == Status.MISSED ? "is still missed" : "isn't reached by any test");
    return result.status().detected() ? PASSED : FAILED;
  }

  /// Prints a finished run's result.
  @FunctionalInterface
  private interface Printer {
    void print(Report report, Optional<Comparison> comparison, List<String> written);
  }

  /// For one format: what hears about mutants as they finish, and what prints the result.
  private record Display(Listener listener, Printer printer) {}

  private static Display display(
      Command command, Prepared prepared, Output output, HumanText human) {
    var oneMutant = command.only().isPresent();
    return switch (output.format()) {
      case "jsonl" -> jsonl(command, prepared, output);
      case "agent" -> {
        var agent = new AgentText(prepared.sources(), command.threshold(), output.words());
        yield new Display(
            output.progress(),
            (report, comparison, written) ->
                output.out().print(agent.render(report, comparison, oneMutant)));
      }
      default ->
          new Display(
              output.progress(),
              (report, comparison, written) ->
                  output
                      .out()
                      .print(
                          human.render(
                              report,
                              comparison,
                              new HumanText.Run(
                                  prepared.startedAt(), prepared.setup(), written, oneMutant))));
    };
  }

  /// JSON Lines: the run's start now, each mutant as it finishes, and the result at the end.
  private static Display jsonl(Command command, Prepared prepared, Output output) {
    var events = new JsonEvents(output.out(), prepared.sources(), command.threshold());
    events.runStarted(prepared.project().name());
    return new Display(
        progressAndEvents(output.progress(), events),
        (report, comparison, written) ->
            events.runFinished(report, comparison, written, command.only().isPresent()));
  }

  private static Listener progressAndEvents(Progress progress, JsonEvents events) {
    return new Listener() {
      @Override
      public void coverageFinished(CoveragePass pass) {
        progress.coverageFinished(pass);
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

  /// What a full run keeps: its comparison with the last run, and the report files it wrote.
  private record Kept(Optional<Comparison> comparison, List<String> written) {
    static final Kept NOTHING = new Kept(Optional.empty(), List.of());
  }

  /// Compares with `last-run.tsv` before replacing it, then writes the report files.
  private static Kept keep(Command command, Prepared prepared, Report report) {
    var lastRun = prepared.work().resolve("last-run.tsv");
    var record =
        RunRecord.of(
            prepared.project(),
            RunRecord.toolOf(List.of(codeSource(Engine.class), workerJar())),
            report);
    var comparison = RunRecord.read(lastRun).map(record::against);
    record.write(lastRun);
    return new Kept(comparison, writeReports(command, prepared, report));
  }

  /// The report files asked for, as they're written: relative to the project where they're in it.
  private static List<String> writeReports(Command command, Prepared prepared, Report report) {
    var directory = prepared.directory();
    var files = new ReportFiles(directory, prepared.sources(), command.threshold(), version());
    var written = new ArrayList<String>();
    for (var requested : command.reports()) {
      var file =
          requested
              .path()
              .map(directory::resolve)
              .orElse(prepared.work().resolve(defaultName(requested.kind())));
      files.write(requested.kind(), report, file);
      written.add(
          file.startsWith(directory) ? directory.relativize(file).toString() : file.toString());
    }
    return written;
  }

  private static int exitCode(Command command, Report report) {
    // One mutant asked for by id passes when it's caught, as `verify` does; a whole run, on its
    // score.
    if (command.only().isPresent()) {
      return report.results().stream().allMatch(result -> result.status().detected())
          ? PASSED
          : FAILED;
    }
    return report.score() >= command.threshold() ? PASSED : FAILED;
  }

  /// `build/mutastic` for the root project; `build/mutastic/data` for `:data`, so each module keeps
  /// its own run record, logs and reports.
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
      words.add(
          directory.startsWith(here)
              ? here.relativize(directory).toString()
              : directory.toString());
    }
    if (!module.equals(":")) {
      words.add(module);
    }
    return words;
  }

  /// A bar on a terminal for people; counter lines in logs; nothing for agents and JSONL readers,
  /// who only want the result.
  private static Progress.Mode progressMode(String choice, String format, Terminal terminal) {
    return switch (choice) {
      case "bar" -> Progress.Mode.BAR;
      case "counter" -> Progress.Mode.COUNTER;
      case "none" -> Progress.Mode.NONE;
      default ->
          format.equals("agent") || format.equals("jsonl")
              ? Progress.Mode.NONE
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

  /// The commands, and whether each works on one mutant. Every one that does must be offered for
  /// each mutant in the output (`MutantCommand`), and its tests check that.
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
      return java.util.Arrays.stream(values())
          .filter(command -> command.word().equals(word))
          .findFirst();
    }
  }

  static final class UsageException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    UsageException(String message) {
      super(message);
    }
  }

  /// A report file to write: `json`, `html` or `xml`, where the default isn't wanted.
  record RequestedReport(String kind, Optional<Path> path) {}

  /// `json` and `html`, unless `--report` says otherwise.
  static final List<RequestedReport> DEFAULT_REPORTS =
      List.of(
          new RequestedReport("json", Optional.empty()),
          new RequestedReport("html", Optional.empty()));

  /// A command line, read.
  ///
  /// @param format empty for the default, which depends on where output goes
  /// @param workers empty for the default, which depends on the machine
  /// @param reports the report files to write after a full run
  record Command(
      boolean verify,
      Path directory,
      String module,
      Optional<String> format,
      int threshold,
      Optional<String> only,
      boolean confirmCatches,
      Optional<Integer> workers,
      String colour,
      String links,
      String progress,
      boolean suggestions,
      List<RequestedReport> reports,
      Optional<String> vocabulary) {
    static Command parse(String[] args) {
      if (args.length == 0 || Subcommand.of(args[0]).isEmpty()) {
        throw new UsageException(args.length == 0 ? "no command" : "unknown command " + args[0]);
      }
      var subcommand = Subcommand.of(args[0]).orElseThrow();
      var parser = new Parser();
      for (String arg : List.of(args).subList(1, args.length)) {
        parser.read(arg);
      }
      return parser.command(subcommand == Subcommand.VERIFY);
    }

    /// `--name=` for an option with a value; the whole argument otherwise.
    private static String option(String arg) {
      int equals = arg.indexOf('=');
      return equals < 0 ? arg : arg.substring(0, equals + 1);
    }

    /// The arguments after the subcommand, read one at a time.
    private static final class Parser {
      private final List<String> positional = new ArrayList<>();
      private Optional<String> format = Optional.empty();
      private int threshold = DEFAULT_THRESHOLD;
      private Optional<String> only = Optional.empty();
      private boolean confirmCatches = false;
      private Optional<Integer> workers = Optional.empty();
      private String colour = "auto";
      private String links = "auto";
      private String progress = "auto";
      private boolean suggestions = true;
      private @Nullable List<RequestedReport> reports = null;
      private Optional<String> vocabulary = Optional.empty();

      void read(String arg) {
        switch (option(arg)) {
          case "--format=" -> format = Optional.of(choice(arg, FORMATS));
          case "--threshold=" -> threshold = number(arg);
          case "--report=" -> report(arg.substring("--report=".length()));
          case "--vocabulary=" -> vocabulary = Optional.of(choice(arg, VOCABULARIES));
          case "--no-suggestions" -> suggestions = false;
          case "--confirm-catches" -> confirmCatches = true;
          case "--only=" -> only = Optional.of(arg.substring("--only=".length()));
          case "--workers=" -> workers = Optional.of(workerCount(arg));
          case "--color=" -> colour = choice(arg, List.of("auto", "always", "never"));
          case "--hyperlinks=" -> links = choice(arg, List.of("auto", "always", "never"));
          case "--progress=" -> progress = choice(arg, List.of("auto", "bar", "counter", "none"));
          default -> positional(arg);
        }
      }

      /// `--report=kind[:path]`.
      private void report(String value) {
        int colon = value.indexOf(':');
        var kind = colon < 0 ? value : value.substring(0, colon);
        if (!kind.equals("none") && !ReportFiles.KINDS.contains(kind)) {
          throw new UsageException(
              "unknown report %s (one of json, html, xml, none)".formatted(kind));
        }
        if (reports == null) {
          reports = new ArrayList<>(); // the first --report replaces the defaults
        }
        if (!kind.equals("none")) {
          reports.add(
              new RequestedReport(
                  kind,
                  colon < 0 ? Optional.empty() : Optional.of(Path.of(value.substring(colon + 1)))));
        }
      }

      private static int workerCount(String arg) {
        int count = number(arg);
        if (count < 1) {
          throw new UsageException("--workers must be at least 1");
        }
        return count;
      }

      private void positional(String arg) {
        if (arg.startsWith("--")) {
          throw new UsageException("unknown option " + arg);
        }
        positional.add(arg);
      }

      Command command(boolean verify) {
        if (verify) {
          if (positional.isEmpty()) {
            throw new UsageException("verify needs a mutant id");
          }
          only = Optional.of(positional.removeFirst());
        }
        return new Command(
            verify,
            directory(),
            module(),
            format,
            threshold,
            only,
            confirmCatches,
            workers,
            colour,
            links,
            progress,
            suggestions,
            reports == null ? DEFAULT_REPORTS : List.copyOf(reports),
            vocabulary);
      }

      private String module() {
        return positional.stream().filter(arg -> arg.startsWith(":")).findFirst().orElse(":");
      }

      private Path directory() {
        return positional.stream()
            .filter(arg -> !arg.startsWith(":"))
            .findFirst()
            .map(Path::of)
            .orElse(Path.of("."));
      }
    }

    /// The whole number in `--name=value`.
    private static int number(String arg) {
      var value = arg.substring(arg.indexOf('=') + 1);
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException e) {
        throw new UsageException(
            "%s needs a whole number, not %s".formatted(arg.substring(0, arg.indexOf('=')), value));
      }
    }

    /// The value of `--name=value`, if it's one of `allowed`.
    private static String choice(String arg, List<String> allowed) {
      var name = arg.substring(2, arg.indexOf('='));
      var value = arg.substring(arg.indexOf('=') + 1);
      if (!allowed.contains(value)) {
        throw new UsageException(
            "unknown %s %s (one of %s)".formatted(name, value, String.join(", ", allowed)));
      }
      return value;
    }
  }
}
