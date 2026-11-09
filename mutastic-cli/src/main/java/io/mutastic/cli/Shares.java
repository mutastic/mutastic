package io.mutastic.cli;

import io.mutastic.api.Listener;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Project;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import io.mutastic.engine.Engine;
import io.mutastic.engine.Shard;
import io.mutastic.report.JsonEvents;
import io.mutastic.report.ShareEvents;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/// A run split across machines, put back together. Each share (`--shard=N/M`) keeps its events in a
/// folder of its own, `shares/N-of-M` in the module's work directory, beside its run record; and
/// `run --from-shards=DIR` reads every share's from DIR, wherever in it they are, and is the whole
/// run made of them: the mutants found again from the code, as the whole run finds them, each with
/// the result its share gave it.
///
/// The shares must be every share of one run: each of the same count, there once and finished, made
/// by the same mutastic on the same code, with no mutant in two. Anything else is refused rather
/// than made into a score, since a score made of the wrong parts passes or fails wrongly.
final class Shares {
  /// The file a share keeps its events in, as `--format=jsonl` writes them.
  static final String EVENTS = "events.jsonl";

  /// One share, read: where from, which it is, and its events.
  private record Read(Path file, JsonEvents.Share share, ShareEvents events) {
    String named() {
      return "share %d of %d (%s)".formatted(share.number(), share.of(), file);
    }
  }

  private final Path directory;
  private final List<Read> shares;

  private Shares(Path directory, List<Read> shares) {
    this.directory = directory;
    this.shares = shares;
  }

  /// Where a share keeps its run record and its events, in the module's work directory.
  static Path folder(Path work, Shard shard) {
    return work.resolve("shares").resolve(shard.number() + "-of-" + shard.of());
  }

  /// A run's shares, from every share's events in `directory`, checked to be all of one run's, made
  /// by this mutastic, `version`.
  static Shares read(Path directory, String version) {
    var shares = eventsIn(directory).stream().map(Shares::share).toList();
    if (shares.isEmpty()) {
      throw new Refused(
          ("%s holds no share's %s: each run with --shard keeps its own in shares/N-of-M, in"
                  + " the module's work directory")
              .formatted(directory, EVENTS));
    }
    var found = new Shares(directory, sorted(shares));
    found.checkCounts();
    found.checkFinished();
    found.checkMadeAlike(version);
    found.checkApart();
    return found;
  }

  private static List<Read> sorted(List<Read> shares) {
    return shares.stream()
        .sorted(Comparator.comparingInt((Read read) -> read.share().number()))
        .toList();
  }

  private static List<Path> eventsIn(Path directory) {
    if (!Files.isDirectory(directory)) {
      throw new Refused("there's no directory " + directory);
    }
    try (Stream<Path> files = Files.walk(directory)) {
      return files.filter(file -> file.getFileName().toString().equals(EVENTS)).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Read share(Path file) {
    ShareEvents events;
    try {
      events = ShareEvents.read(file);
    } catch (IllegalArgumentException e) {
      throw new Refused("%s isn't a share's events: %s".formatted(file, e.getMessage()));
    }
    var share =
        events
            .share()
            .orElseThrow(
                () ->
                    new Refused(
                        "%s isn't a share's events: its run-started names no share"
                            .formatted(file)));
    return new Read(file, share, events);
  }

  /// Every share of the same count, each there once.
  private void checkCounts() {
    var first = shares.getFirst();
    for (Read read : shares) {
      if (read.share().of() != first.share().of()) {
        throw new Refused(
            "%s and %s are shares of runs split differently: put together only one run's shares"
                .formatted(first.named(), read.named()));
      }
    }
    var seen = new HashMap<Integer, Read>();
    for (Read read : shares) {
      var before = seen.put(read.share().number(), read);
      if (before != null) {
        throw new Refused(
            "share %d of %d is there twice, in %s and %s"
                .formatted(read.share().number(), read.share().of(), before.file(), read.file()));
      }
    }
    checkNoneMissing(seen.keySet());
  }

  private void checkNoneMissing(java.util.Set<Integer> numbers) {
    int of = shares.getFirst().share().of();
    for (int number = 1; number <= of; number++) {
      if (!numbers.contains(number)) {
        throw new Refused(
            "share %d of %d is missing from %s: its run didn't keep its %s there"
                .formatted(number, of, directory, EVENTS));
      }
    }
  }

  private void checkFinished() {
    for (Read read : shares) {
      if (!read.events().finished()) {
        throw new Refused(read.named() + " didn't finish: its events stop before run-finished");
      }
      var partial = read.events().partial();
      if (partial.isPresent()) {
        throw new Refused(
            "%s %s, so it isn't a share of a whole run".formatted(read.named(), partial.get()));
      }
    }
  }

  /// Every share made by this mutastic, on the same code as the others.
  private void checkMadeAlike(String version) {
    var first = shares.getFirst();
    for (Read read : shares) {
      if (!read.share().version().equals(version)) {
        throw new Refused(
            "%s was run by mutastic %s, and this is %s: run the shares and put them together"
                    .formatted(read.named(), read.share().version(), version)
                + " with the same mutastic");
      }
      if (!read.share().code().equals(first.share().code())) {
        throw new Refused(
            ("%s and %s ran on different code: every share of a run runs on the same commit,"
                    + " built the same way")
                .formatted(first.named(), read.named()));
      }
    }
  }

  /// No mutant in two shares.
  private void checkApart() {
    var holder = new HashMap<String, Read>();
    for (Read read : shares) {
      for (String id : read.events().ids()) {
        var before = holder.put(id, read);
        if (before != null) {
          throw new Refused(
              "%s is in %s and in %s: a mutant is in one share of a run"
                  .formatted(id, before.named(), read.named()));
        }
      }
    }
  }

  /// The whole run of `project`, whose code and tests hash to `code` and in which a run finds the
  /// mutants `found`: each with its share's result, told to `listener` as a run tells it.
  Report whole(Project project, String code, Engine.Mutants found, Listener listener) {
    var first = shares.getFirst();
    if (!first.events().project().equals(project.name())) {
      throw new Refused(
          "the shares in %s are of %s, not %s"
              .formatted(directory, first.events().project(), project.name()));
    }
    if (!first.share().code().equals(code)) {
      throw new Refused(
          ("the shares in %s ran on other code than this: put them together on the commit they"
                  + " ran on, built the same way")
              .formatted(directory));
    }
    var results = results(found.all());
    tell(results, listener);
    return report(project, results, found);
  }

  /// Each mutant a run finds, with the result its share gave it.
  private List<MutantResult> results(List<Mutant> mutants) {
    var byId = new HashMap<String, Read>();
    shares.forEach(read -> read.events().ids().forEach(id -> byId.put(id, read)));
    var results = new ArrayList<MutantResult>();
    for (Mutant mutant : mutants) {
      var read = byId.remove(mutant.id());
      if (read == null || skipped(read, mutant) != mutant.skipped().isPresent()) {
        throw unlike(mutant.id());
      }
      results.add(read.events().result(mutant));
    }
    var notFound = byId.keySet().stream().sorted().findFirst();
    if (notFound.isPresent()) {
      throw unlike(notFound.get());
    }
    return results;
  }

  private static boolean skipped(Read read, Mutant mutant) {
    return read.events().status(mutant.id()) == Status.SKIPPED;
  }

  private Refused unlike(String id) {
    return new Refused(
        ("the shares in %s didn't run the mutants this run finds (%s, for one): they ran with"
                + " other operators, settings or ignores than these")
            .formatted(directory, id));
  }

  /// Each result as a run tells it: as it finishes, and each source file's once its last is in.
  private static void tell(List<MutantResult> results, Listener listener) {
    var lastOfFile = new LinkedHashMap<String, Integer>();
    for (int at = 0; at < results.size(); at++) {
      lastOfFile.put(fileOf(results.get(at).mutant()), at);
    }
    var byFile = new LinkedHashMap<String, List<MutantResult>>();
    for (int at = 0; at < results.size(); at++) {
      var result = results.get(at);
      var file = fileOf(result.mutant());
      var ofFile = byFile.computeIfAbsent(file, ignored -> new ArrayList<>());
      ofFile.add(result);
      listener.mutantFinished(result);
      if (lastOfFile.getOrDefault(file, -1) == at) {
        listener.fileFinished(file, List.copyOf(ofFile));
      }
    }
  }

  /// The top-level class a mutant's source file is named for: `com.example.Pricing` for
  /// `com.example.Pricing$Tier`.
  private static String fileOf(Mutant mutant) {
    var name = mutant.binaryName();
    int dollar = name.indexOf('$', name.lastIndexOf('.') + 1);
    return dollar < 0 ? name : name.substring(0, dollar);
  }

  /// The whole run's report: the shares' tests and flaky tests together, the most workers any ran
  /// side by side with every share's replacements, and the longest share's time.
  private Report report(Project project, List<MutantResult> results, Engine.Mutants found) {
    var events = shares.stream().map(Read::events).toList();
    int workers = events.stream().mapToInt(ShareEvents::workers).max().orElse(1);
    int replaced =
        events.stream().mapToInt(share -> share.workersStarted() - share.workers()).sum();
    return new Report(
        project.name(),
        events.getFirst().jdk(),
        results,
        events.stream().mapToInt(ShareEvents::tests).max().orElse(0),
        List.copyOf(
            new LinkedHashSet<>(events.stream().flatMap(share -> share.flaky().stream()).toList())),
        longest(events.stream().map(ShareEvents::coverage)),
        workers,
        workers + replaced,
        longest(events.stream().map(ShareEvents::took)),
        List.of(),
        Optional.empty(),
        found.stale(),
        events.stream().flatMap(share -> share.flakyEvidence().stream()).distinct().toList());
  }

  private static Duration longest(Stream<Duration> durations) {
    return durations.max(Comparator.naturalOrder()).orElse(Duration.ZERO);
  }

  /// How many shares there are, and how many mutants they ran.
  String summary() {
    long mutants = shares.stream().mapToLong(read -> read.events().ids().size()).sum();
    return "shares    %d from %s · %d mutants".formatted(shares.size(), directory, mutants);
  }

  /// Shares that can't be put together as one run, and why: a usage error, as the command's input
  /// is what's wrong.
  static final class Refused extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    Refused(String message) {
      super(message);
    }
  }

  /// Keeps a share's events in `file`, written by `writing`, in place of any it kept before.
  static void keep(Path file, java.util.function.Consumer<PrintStream> writing) {
    try {
      Files.createDirectories(file.getParent());
      try (var out = new PrintStream(Files.newOutputStream(file), false, StandardCharsets.UTF_8)) {
        writing.accept(out);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// Forgets the events a share kept in `file` before: a share that doesn't finish this time
  /// mustn't leave the last time's to be put together with the other shares'.
  static void forget(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
