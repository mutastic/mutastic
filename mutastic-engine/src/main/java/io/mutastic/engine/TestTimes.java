package io.mutastic.engine;

import io.mutastic.api.ClassMutator;
import io.mutastic.api.Mutant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/// What a coverage pass measured, kept so `mutastic mutants --operators=…` can say what a set of
/// operators would add to a run's tests without running one: for each line, the time the tests that
/// reach it took between them.
///
/// The file is one entry per line, tab-separated: `kind <run or coverage pass>`, `at <instant>`,
/// `operators <ids>`, `code <hash>`, then `base <id>` for each mutant of that set, `measured
/// <class>` for each class the pass instrumented, `line <key> <millis>`, `method <key>` for each
/// method a test reached, and `every <millis>`.
///
/// @param kind what measured it, as an estimate names it: `run` or `coverage pass`
/// @param at when
/// @param operators the ids of the operators `base` is the mutants of, sorted
/// @param code the hash of the code under test, not its tests: `mutants` doesn't build them
/// @param base the mutants of `operators`, by id: an estimate is of the mutants beyond them
/// @param measured what the pass measured
public record TestTimes(
    String kind, Instant at, String operators, String code, Set<String> base, Measured measured) {

  /// What `kind` a whole run's record is.
  public static final String RUN = "run";

  /// What `kind` the record of `mutants --estimate`'s pass is.
  public static final String COVERAGE_PASS = "coverage pass";

  /// To the second: an estimate names its run to the minute.
  public TestTimes {
    at = at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    base = Set.copyOf(base);
  }

  /// What a coverage pass found, by where a mutant is.
  ///
  /// @param classes the classes the pass instrumented, by internal name: a mutant in another has no
  ///   time measured
  /// @param lines for each line, keyed as [Coverage] keys it, what the tests that reach it took
  ///   between them; a line no test reached isn't there
  /// @param methods the methods a test reached, as `class#name(descriptor)`
  /// @param every what every test took between them
  public record Measured(
      Set<String> classes, Map<String, Duration> lines, Set<String> methods, Duration every) {
    public Measured {
      classes = Set.copyOf(classes);
      lines = Map.copyOf(lines);
      methods = Set.copyOf(methods);
    }

    /// Whether the pass measured where `mutant` is: one that's skipped, or a twin, never runs, so
    /// it needs no measure.
    boolean holds(Mutant mutant) {
      return !runs(mutant) || classes.contains(mutant.className());
    }

    /// What `mutant` should take, as a run reckons it: its covering tests' time, every test's for
    /// one that mutates a whole method or class. None for one that's skipped, a twin, or that no
    /// test reaches.
    Duration cost(Mutant mutant) {
      if (!runs(mutant)) {
        return Duration.ZERO;
      }
      if (mutant.operator() instanceof ClassMutator mutator) {
        boolean reached =
            mutator.reach() != ClassMutator.Reach.METHOD
                || methods.contains(Coverage.methodKey(mutant));
        return reached ? every : Duration.ZERO;
      }
      return lines.getOrDefault(Coverage.key(mutant), Duration.ZERO);
    }

    private static boolean runs(Mutant mutant) {
      return mutant.skipped().isEmpty();
    }
  }

  /// What `mutants` would take to run, at most: a caught one stops at its first failing test.
  ///
  /// @param mutants how many
  /// @param unmeasured how many are where the pass measured nothing, so have no time to give
  /// @param upTo what the tests of those measured would take between them
  public record Estimate(int mutants, int unmeasured, Duration upTo) {}

  /// What `mutants` would take, by what this pass measured.
  public Estimate estimate(List<Mutant> mutants) {
    int unmeasured = 0;
    var upTo = Duration.ZERO;
    for (var mutant : mutants) {
      if (measured.holds(mutant)) {
        upTo = upTo.plus(measured.cost(mutant));
      } else {
        unmeasured++;
      }
    }
    return new Estimate(mutants.size(), unmeasured, upTo);
  }

  /// `mutants`, less those of `base`.
  public List<Mutant> beyondBase(List<Mutant> mutants) {
    return mutants.stream().filter(mutant -> !base.contains(mutant.id())).toList();
  }

  /// Where a work directory keeps its record.
  public static Path file(Path work) {
    return work.resolve("test-times.tsv");
  }

  /// The record in `file`: empty if there's none, or it can't be read.
  public static Optional<TestTimes> read(Path file) {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(parsed(Files.readAllLines(file)));
    } catch (IOException | RuntimeException e) {
      return Optional.empty(); // an unreadable record is no record: the next run writes a new one
    }
  }

  private static TestTimes parsed(List<String> lines) {
    var fields = new LinkedHashMap<String, String>();
    var base = new TreeSet<String>();
    var classes = new TreeSet<String>();
    var methods = new TreeSet<String>();
    var times = new LinkedHashMap<String, Duration>();
    for (String line : lines) {
      var parts = line.split("\t", -1);
      switch (parts[0]) {
        case "base" -> base.add(parts[1]);
        case "measured" -> classes.add(parts[1]);
        case "method" -> methods.add(parts[1]);
        case "line" -> times.put(parts[1], Duration.ofMillis(Long.parseLong(parts[2])));
        default -> fields.put(parts[0], parts[1]);
      }
    }
    var every = Duration.ofMillis(Long.parseLong(required(fields, "every")));
    return new TestTimes(
        required(fields, "kind"),
        Instant.parse(required(fields, "at")),
        required(fields, "operators"),
        required(fields, "code"),
        base,
        new Measured(classes, times, methods, every));
  }

  private static String required(Map<String, String> fields, String name) {
    var value = fields.get(name);
    if (value == null) {
      throw new IllegalArgumentException("no " + name);
    }
    return value;
  }

  public void write(Path file) {
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, lines());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private List<String> lines() {
    var lines =
        new ArrayList<String>(
            List.of("kind\t" + kind, "at\t" + at, "operators\t" + operators, "code\t" + code));
    new TreeSet<>(base).forEach(id -> lines.add("base\t" + id));
    new TreeSet<>(measured.classes()).forEach(name -> lines.add("measured\t" + name));
    new TreeSet<>(measured.methods()).forEach(method -> lines.add("method\t" + method));
    new java.util.TreeMap<>(measured.lines())
        .forEach((key, took) -> lines.add("line\t" + key + "\t" + took.toMillis()));
    lines.add("every\t" + measured.every().toMillis());
    return lines;
  }
}
