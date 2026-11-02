package io.mutastic.cli;

import io.mutastic.api.BuildTool;
import io.mutastic.api.Comparison;
import io.mutastic.api.CoveragePass;
import io.mutastic.api.Listener;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Project;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import io.mutastic.engine.Engine;
import io.mutastic.engine.RunRecord;
import io.mutastic.report.AgentText;
import io.mutastic.report.Explanation;
import io.mutastic.report.HumanText;
import io.mutastic.report.Invocation;
import io.mutastic.report.JsonEvents;
import io.mutastic.report.ReportFiles;
import io.mutastic.report.SourceView;
import io.mutastic.report.Style;
import io.mutastic.report.Vocabulary;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
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
/// label ends for a Nerd Font, and `MUTASTIC_PILLS=off` draws labels as coloured words, not blocks.
/// A full run also writes report files, by default `build/mutastic/mutation.json` and
/// `mutation.html`.
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

  /// `--tests-from=dependents`: every module that depends on the one run.
  static final String DEPENDENTS = "dependents";

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
    } catch (BuildTool.SetupFailed | Engine.UnknownMutant e) {
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
    var tool = BuildTools.of(command.directory().toAbsolutePath().normalize());
    progress.started(version(), tool.name());
    var prepared = prepare(command, tool);
    var human = humanText(command, output, prepared.sources());
    showFileLines(command, output, human, prepared);
    var engine = new Engine(workerJar(), prepared.work().resolve("worker.log"));
    var options = options(command, terminal, prepared.directory());
    var passes = new Passes(engine, options, progress, tool);
    if (command.subcommand() != Subcommand.RUN) {
      return single(command, prepared, passes, output);
    }
    var display = display(command, prepared, output, human);
    var tested = passes.run(command, prepared, display);
    progress.finished();
    // Only full runs compare, are kept and write reports: one mutant says nothing about the rest.
    var kept =
        command.only().isEmpty()
            ? keep(command, prepared, tested.report(), tested.otherTests())
            : Kept.NOTHING;
    display.printer().print(tested.report(), kept.comparison(), kept.written());
    return exitCode(command, tested.report());
  }

  /// `verify`, which runs one mutant's tests and says whether it's caught; or `ignore`, which runs
  /// none and records a decision.
  private static int single(Command command, Prepared prepared, Passes passes, Output output) {
    var out = output.out();
    if (command.explain()) {
      return explained(command, prepared, passes, output);
    }
    if (command.ignore().isPresent()) {
      var where =
          new IgnoreCommand.Where(
              prepared.directory(),
              prepared.sources(),
              shown(prepared.directory().resolve(Ignores.FILE)));
      // Removing needs no mutant: its code may be gone.
      return command.ignore().get().remove()
          ? IgnoreCommand.remove(command.only().orElseThrow(), where, out)
          : IgnoreCommand.run(
              command.ignore().get(),
              where,
              passes.engine().find(prepared.project(), passes.options()),
              out);
    }
    var report = passes.engine().run(prepared.project(), passes.options(), passes.progress());
    passes.progress().finished();
    return verified(report.results().getFirst(), out);
  }

  /// `explain`: everything known of one mutant, with how the last full run went.
  private static int explained(Command command, Prepared prepared, Passes passes, Output output) {
    var lastRun = lastRun(prepared, command.only().orElseThrow());
    var report = passes.engine().run(prepared.project(), passes.options(), passes.progress());
    passes.progress().finished();
    var result = report.results().getFirst();
    output.out().print(humanText(command, output, prepared.sources()).explained(result));
    output
        .out()
        .print(
            new Explanation(prepared.sources(), output.style()).render(result.mutant(), lastRun));
    return PASSED;
  }

  /// How the last full run of this project went for the mutant `id`, if there was one.
  private static Optional<Explanation.LastRun> lastRun(Prepared prepared, String id) {
    return RunRecord.read(prepared.work().resolve("last-run.tsv"))
        .filter(record -> record.isOf(prepared.project()))
        .flatMap(record -> Optional.ofNullable(record.results().get(id)))
        .map(entry -> new Explanation.LastRun(entry.status(), entry.caughtBy()));
  }

  /// `ignore`'s own: `--prune` what no longer holds, `--remove` one by id, or else ignore one, for
  /// `reason`.
  record Ignoring(boolean prune, boolean remove, String reason) {}

  /// A file as the person would type it from where mutastic ran: relative if it's below, else
  /// absolute.
  private static String shown(Path file) {
    var here = Path.of("").toAbsolutePath().normalize();
    var absolute = file.toAbsolutePath().normalize();
    return absolute.startsWith(here) ? here.relativize(absolute).toString() : absolute.toString();
  }

  /// Each source file's line as it finishes, for people. With other modules' tests, a mutant's
  /// result is known only once they've run too, so not then.
  private static void showFileLines(
      Command command, Output output, HumanText human, Prepared prepared) {
    if (PEOPLE.contains(output.format()) && command.testsFrom().isEmpty()) {
      output
          .progress()
          .showFiles(results -> human.fileFinished(prepared.project().name(), results));
    }
  }

  /// The merged result, and the other modules' test directories that ran.
  private record Tested(Report report, List<Path> otherTests) {}

  /// The run: the module's own tests, then each `--tests-from` module's over the mutants still
  /// uncaught, as that module's own build runs them (its classpath, with this module's mutated
  /// classes in place of its jar), merged.
  private record Passes(Engine engine, Engine.Options options, Progress progress, BuildTool tool) {
    Tested run(Command command, Prepared prepared, Display display) {
      var merged = engine.run(prepared.project(), options, display.listener());
      var otherTests = new ArrayList<Path>();
      for (String module : modules(command, prepared)) {
        var uncaught = uncaught(merged);
        if (uncaught.isEmpty()) {
          break;
        }
        var runner = runner(prepared, module);
        otherTests.addAll(runner.testClasses());
        progress.testsFrom(module);
        var pass =
            engine.run(
                prepared.project().runningTestsOf(runner), options.among(uncaught), counting());
        merged = merged.merge(pass);
      }
      return new Tested(merged, otherTests);
    }

    /// The `--tests-from` modules, with `dependents` as the modules that depend on this one.
    private List<String> modules(Command command, Prepared prepared) {
      var modules = new java.util.LinkedHashSet<String>();
      for (String named : command.testsFrom()) {
        if (!named.equals(DEPENDENTS)) {
          modules.add(named);
          continue;
        }
        var found =
            tool.dependents(
                prepared.directory(), command.module(), prepared.work().resolve("dependents"));
        if (found.isEmpty()) {
          progress.note(
              "no module depends on %s: only its own tests ran".formatted(command.module()));
        }
        modules.addAll(found);
      }
      return List.copyOf(modules);
    }

    /// The other module, read into a directory of its own below this module's.
    private Project runner(Prepared prepared, String module) {
      var work =
          prepared.work().resolve("tests-from").resolve(module.substring(1).replace(':', '/'));
      return tool.read(prepared.directory(), module, work, prepared.directory().toString());
    }

    private static java.util.Set<String> uncaught(Report report) {
      return report.results().stream()
          .filter(result -> !result.status().detected() && result.status() != Status.SKIPPED)
          .map(result -> result.mutant().id())
          .collect(java.util.stream.Collectors.toSet());
    }

    /// Progress only: each result is shown once every module's tests have run.
    private Listener counting() {
      return new Listener() {
        @Override
        public void coverageFinished(CoveragePass found) {
          progress.coverageFinished(found);
        }

        @Override
        public void mutantFinished(MutantResult result) {
          progress.mutantFinished(result);
        }
      };
    }
  }

  private static void printUsage(PrintStream err) {
    err.println(
        "usage: mutastic run [dir] [:module] [--format=full|brief|concise|grouped|agent|jsonl] [--threshold=N]");
    err.println(
        "                    [--only=ID] [--since[=REF]] [--tests-from=:module|dependents]...");
    err.println("                    [--vocabulary=plain|classic]");
    err.println(
        "                    [--confirm-catches] [--workers=N] [--color=auto|always|never]");
    err.println(
        "                    [--hyperlinks=auto|always|never] [--progress=auto|bar|counter|none]");
    err.println("                    [--no-suggestions] [--report=json|html|xml|none[:path]]...");
    err.println("       mutastic verify ID [dir] [:module]");
    err.println("       mutastic explain ID [dir] [:module]");
    err.println("       mutastic ignore ID [dir] [:module] --reason=WHY | --remove ID | --prune");
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
    return style.withLabels(labels(terminal.defaultLabels()));
  }

  private static Style.Labels labels(String chosen) {
    return switch (chosen) {
      case "square" -> Style.Labels.SQUARE;
      case "rounded" -> Style.Labels.ROUNDED;
      case "off" -> Style.Labels.OFF;
      default ->
          throw new UsageException(
              "unknown labels %s in MUTASTIC_PILLS (one of square, rounded, off)"
                  .formatted(chosen));
    };
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

  private static Prepared prepare(Command command, BuildTool tool) {
    long start = System.nanoTime();
    var startedAt = LocalTime.now(java.time.ZoneId.systemDefault()); // the reader's clock
    var directory = command.directory().toAbsolutePath().normalize();
    var work = workDirectory(tool, directory, command.module());
    var here = Path.of("").toAbsolutePath().normalize();
    var target = target(here, directory, command.module());
    var project =
        command.setup().isPresent()
            ? tool.read(directory, command.module(), command.setup().get())
            : tool.read(
                directory, command.module(), work, String.join(" ", target(here, directory, ":")));
    var setup = Duration.ofNanos(System.nanoTime() - start);
    var sources =
        new SourceView(directory, project.sources(), project.testSources())
            .withInvocation(Invocation.of(command.commands(), target));
    return new Prepared(directory, work, project, sources, startedAt, setup);
  }

  /// Where mutastic keeps the module's run record, logs and reports, made if it isn't there yet:
  /// with a setup a build plugin wrote, no build has run to make it.
  private static Path workDirectory(BuildTool tool, Path directory, String module) {
    var work = tool.workDirectory(directory, module);
    try {
      Files.createDirectories(work);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return work;
  }

  private static HumanText humanText(Command command, Output output, SourceView sources) {
    return new HumanText(
        sources, command.threshold(), output.style(), detail(output.format()), output.words());
  }

  /// What the engine runs with: the command's choices, and the project's `mutastic.yaml`.
  private static Engine.Options options(Command command, Terminal terminal, Path directory) {
    return new Engine.Options(
        command.only(),
        command.confirmCatches(),
        command.workers().orElse(Engine.defaultWorkers()),
        command.suggestions() && !command.verify(),
        terminal.auditArid(),
        java.util.Set.of(),
        Configuration.read(directory)
            .policy(directory)
            .withIgnores(Ignores.read(directory).stream().map(Ignores.Entry::ignore).toList()),
        command.since().map(since -> new Git(directory).changedSince(since)));
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
    if (!command.testsFrom().isEmpty()) {
      // Each mutant's result is known only once every module's tests have run: all at the end.
      return new Display(
          output.progress(),
          (report, comparison, written) -> {
            report.results().forEach(events::mutantFinished);
            events.runFinished(report, comparison, written, command.only().isPresent());
          });
    }
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
  private static Kept keep(
      Command command, Prepared prepared, Report report, List<Path> otherTests) {
    if (report.scope().isPresent()) {
      // A change's score says nothing about the whole module's: no comparison, no record.
      return new Kept(Optional.empty(), writeReports(command, prepared, report));
    }
    var lastRun = prepared.work().resolve("last-run.tsv");
    var record =
        RunRecord.of(
            prepared.project(),
            otherTests,
            RunRecord.toolOf(List.of(codeSource(Engine.class), workerJar())),
            report);
    // Only an earlier run of the same project, in the same place, says anything about this one.
    var comparison = RunRecord.read(lastRun).filter(record::sameProjectAs).map(record::against);
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
    return report.passes(command.threshold()) ? PASSED : FAILED;
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
    VERIFY(true),
    IGNORE(true),
    EXPLAIN(true);

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
      Subcommand subcommand,
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
      Optional<String> vocabulary,
      List<String> testsFrom,
      Optional<String> since,
      Optional<Ignoring> ignore,
      Optional<Path> setup,
      String commands) {
    boolean verify() {
      return subcommand == Subcommand.VERIFY;
    }

    boolean explain() {
      return subcommand == Subcommand.EXPLAIN;
    }

    /// The same command, for `ignore`.
    Command ignoring(Ignoring how) {
      return new Command(
          subcommand,
          directory,
          module,
          format,
          threshold,
          only,
          confirmCatches,
          workers,
          colour,
          links,
          progress,
          suggestions,
          reports,
          vocabulary,
          testsFrom,
          since,
          Optional.of(how),
          setup,
          commands);
    }

    static Command parse(String[] args) {
      if (args.length == 0 || Subcommand.of(args[0]).isEmpty()) {
        throw new UsageException(args.length == 0 ? "no command" : "unknown command " + args[0]);
      }
      var subcommand = Subcommand.of(args[0]).orElseThrow();
      var parser = new Parser();
      for (String arg : List.of(args).subList(1, args.length)) {
        parser.read(arg);
      }
      return parser.command(subcommand);
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
      private final List<String> testsFrom = new ArrayList<>();
      private Optional<String> since = Optional.empty();
      private String reason = "";
      private boolean prune = false;
      private boolean remove = false;
      private Optional<Path> setup = Optional.empty();
      private String commands = "mutastic";

      void read(String arg) {
        // The build plugins' form of an option, `-Dmutastic.only=ID`: how they pass their settings,
        // and how the commands they offer write them, so each parses back to the command it
        // stands for.
        var properties = Invocation.options(arg);
        if (properties.isPresent()) {
          properties.get().forEach(this::readOption);
        } else {
          readOption(arg);
        }
      }

      private void readOption(String arg) {
        switch (option(arg)) {
          case "--format=" -> format = Optional.of(choice(arg, FORMATS));
          case "--threshold=" -> threshold = number(arg);
          case "--report=" -> report(arg.substring("--report=".length()));
          case "--vocabulary=" -> vocabulary = Optional.of(choice(arg, VOCABULARIES));
          case "--no-suggestions" -> suggestions = false;
          case "--confirm-catches" -> confirmCatches = true;
          case "--only=" -> only = Optional.of(arg.substring("--only=".length()));
          case "--tests-from=" -> testsFrom.add(module(arg.substring("--tests-from=".length())));
          case "--since" -> since = Optional.of("");
          case "--reason=" -> reason = arg.substring("--reason=".length()).strip();
          case "--prune" -> prune = true;
          case "--remove" -> remove = true;
          case "--since=" -> since = Optional.of(arg.substring("--since=".length()));
          case "--workers=" -> workers = Optional.of(workerCount(arg));
          case "--color=" -> colour = choice(arg, List.of("auto", "always", "never"));
          case "--hyperlinks=" -> links = choice(arg, List.of("auto", "always", "never"));
          case "--progress=" -> progress = choice(arg, List.of("auto", "bar", "counter", "none"));
          default -> buildPluginOption(arg);
        }
      }

      /// The build plugins' own options, as they run inside the build: the setup they wrote, and
      /// their goals as the commands the output offers. Anything else is a directory or module.
      private void buildPluginOption(String arg) {
        switch (option(arg)) {
          case "--setup=" -> setup = Optional.of(Path.of(arg.substring("--setup=".length())));
          case "--commands=" -> commands = commands(arg.substring("--commands=".length()));
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

      private static String commands(String value) {
        try {
          Invocation.of(value, List.of());
          return value;
        } catch (IllegalArgumentException e) {
          throw new UsageException(String.valueOf(e.getMessage()));
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

      Command command(Subcommand subcommand) {
        if (subcommand == Subcommand.IGNORE) {
          return ignoring();
        }
        if (subcommand != Subcommand.RUN) {
          only = Optional.of(mutantId(subcommand.word()));
        }
        return built(subcommand);
      }

      /// `ignore ID --reason=…`, or `ignore --prune`.
      private Command ignoring() {
        if (!prune) {
          only = Optional.of(mutantId("ignore"));
          if (!remove && reason.isBlank()) {
            throw new UsageException(
                "ignore needs a reason, which every ignore says: --reason=\"…\"");
          }
        }
        return built(Subcommand.IGNORE).ignoring(new Ignoring(prune, remove, reason));
      }

      private String mutantId(String subcommand) {
        if (positional.isEmpty()) {
          throw new UsageException(subcommand + " needs a mutant id");
        }
        return positional.removeFirst();
      }

      private Command built(Subcommand subcommand) {
        return new Command(
            subcommand,
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
            vocabulary,
            List.copyOf(testsFrom),
            since,
            Optional.empty(),
            setup,
            commands);
      }

      /// `:app`: another module, named as the module run is; or `dependents`, every module that
      /// depends on this one.
      private static String module(String value) {
        if (!value.equals(DEPENDENTS) && (!value.startsWith(":") || value.length() < 2)) {
          throw new UsageException(
              "--tests-from takes a module, as :app, or dependents; not " + value);
        }
        return value;
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
