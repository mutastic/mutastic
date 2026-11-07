package io.mutastic.engine;

import io.mutastic.api.Comparison;
import io.mutastic.api.Operator;
import io.mutastic.api.Project;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// What a run found, kept for the next run to compare with: each mutant's status and catching test,
/// the score, and a hash of the compiled code and tests.
///
/// The file is one entry per line, tab-separated, so it diffs well: `project <class directories>`,
/// `operators <ids>`, `code <hash>`, `tool <hash>`, `score <score>`, then `<id> <status> <caught by
/// or ->`.
///
/// @param project which project it's of: its class directories, absolute, so a run is only ever
///   compared with an earlier run of the same project, in the same place; empty in a record from
///   before it was kept, which is compared with nothing
/// @param operators the ids of the operators the run used, sorted: a score is a share of what they
///   make, so it says nothing about a run under others. Empty in a record from before it was kept:
///   that run's set can't be known, so it's compared with nothing, and only the run after it is
/// @param code the hash of every class file in the code and the tests
/// @param tool the hash of the mutastic that ran: a new mutastic can change results too
public record RunRecord(
    String project,
    String operators,
    String code,
    String tool,
    OptionalDouble score,
    Map<String, Entry> results) {

  public record Entry(Status status, Optional<String> caughtBy) {}

  public RunRecord {
    results = Map.copyOf(results);
  }

  public static RunRecord of(Project project, String tool, Report report) {
    return of(project, List.of(), tool, report);
  }

  /// A run that also ran the tests in `otherTests`, another module's: they're part of the code it
  /// ran, so a run with other tests is never "the same code".
  public static RunRecord of(Project project, List<Path> otherTests, String tool, Report report) {
    var results = new LinkedHashMap<String, Entry>();
    report
        .results()
        .forEach(
            result ->
                results.put(result.mutant().id(), new Entry(result.status(), result.caughtBy())));
    var roots = new java.util.ArrayList<>(project.classes());
    roots.addAll(project.testClasses());
    roots.addAll(otherTests);
    return new RunRecord(projectOf(project), "", codeOf(roots), tool, report.score(), results);
  }

  /// The same record, of a run that used these operators.
  public RunRecord under(List<Operator> active) {
    var ids = active.stream().map(Operator::id).sorted().collect(Collectors.joining(","));
    return new RunRecord(project, ids, code, tool, score, results);
  }

  /// The hash of the files mutastic runs from: its engine and worker jars (or class directories),
  /// so a rebuilt mutastic counts as a different one even at the same version.
  public static String toolOf(List<Path> files) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (Path root : files) {
        try (Stream<Path> walk = Files.walk(root)) {
          for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
            digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(file));
          }
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("every JDK has SHA-256", e);
    }
  }

  /// The record in `file`, whatever it's of: empty if there's none, or it can't be read.
  public static Optional<RunRecord> read(Path file) {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return parsed(Files.readAllLines(file));
    } catch (IOException | RuntimeException e) {
      return Optional.empty(); // an unreadable record is no record: the next run writes a new one
    }
  }

  private static Optional<RunRecord> parsed(List<String> lines) {
    var fields = new LinkedHashMap<String, String>();
    var results = new LinkedHashMap<String, Entry>();
    for (String line : lines) {
      var parts = line.split("\t", -1);
      if (HEADERS.contains(parts[0])) {
        fields.put(parts[0], parts[1]);
      } else {
        results.put(parts[0], entry(parts));
      }
    }
    return Optional.ofNullable(fields.get("code"))
        .map(
            code ->
                new RunRecord(
                    fields.getOrDefault("project", ""),
                    fields.getOrDefault("operators", ""),
                    code,
                    fields.getOrDefault("tool", ""),
                    score(fields.getOrDefault("score", "-")),
                    results));
  }

  /// The lines before the results, by their first field.
  private static final java.util.Set<String> HEADERS =
      java.util.Set.of("project", "operators", "code", "tool", "score");

  /// `85.7`, or `-` for a run with nothing to score.
  private static OptionalDouble score(String text) {
    return text.equals("-") ? OptionalDouble.empty() : OptionalDouble.of(Double.parseDouble(text));
  }

  /// A result from its line: the status, then the test that caught it, `-` for none.
  private static Entry entry(String[] parts) {
    return new Entry(
        Status.valueOf(parts[1]), parts[2].equals("-") ? Optional.empty() : Optional.of(parts[2]));
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
            List.of(
                "project\t" + project,
                "operators\t" + operators,
                "code\t" + code,
                "tool\t" + tool,
                "score\t" + (score.isPresent() ? String.valueOf(score.getAsDouble()) : "-")));
    results.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry ->
                lines.add(
                    entry.getKey()
                        + "\t"
                        + entry.getValue().status().name()
                        + "\t"
                        + entry.getValue().caughtBy().orElse("-")));
    return lines;
  }

  /// How this run differs from `previous`. Whether `previous` is of the same project: else
  /// comparing the two says nothing.
  public boolean sameProjectAs(RunRecord previous) {
    return !project.isEmpty() && project.equals(previous.project());
  }

  /// Whether a comparison with `previous` means anything: the same project, under the same
  /// operators.
  public boolean comparesWith(RunRecord previous) {
    return sameProjectAs(previous)
        && !operators.isEmpty()
        && operators.equals(previous.operators());
  }

  /// Whether this is a record of `project`, as it is now.
  public boolean isOf(Project project) {
    return !this.project.isEmpty() && this.project.equals(projectOf(project));
  }

  /// `/code/app/build/classes/java/main`: which project a record is of.
  static String projectOf(Project project) {
    return project.classes().stream()
        .map(path -> path.toAbsolutePath().normalize().toString())
        .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
  }

  public Comparison against(RunRecord previous) {
    boolean sameCode = previous.code.equals(code);
    boolean sameTool = previous.tool.equals(tool);
    var flips = new ArrayList<Comparison.Flip>();
    results.forEach(
        (id, now) -> {
          var before = previous.results.get(id);
          if (before != null
              && before.status().detected() != now.status().detected()
              && before.status() != Status.SKIPPED
              && now.status() != Status.SKIPPED) {
            var caught = now.status().detected() ? now : before;
            flips.add(new Comparison.Flip(id, before.status(), now.status(), caught.caughtBy()));
          }
        });
    flips.sort(java.util.Comparator.comparing(Comparison.Flip::id));
    return new Comparison(previous.score, sameCode, sameTool, flips);
  }

  /// SHA-256 over every class file's path and bytes, in a stable order.
  static String codeOf(Project project) {
    return codeOf(
        Stream.of(project.classes(), project.testClasses()).flatMap(List::stream).toList());
  }

  private static String codeOf(List<Path> roots) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (Path root : roots) {
        if (!Files.isDirectory(root)) {
          continue;
        }
        try (Stream<Path> files = Files.walk(root)) {
          for (Path file :
              files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
            digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(file));
          }
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("every JDK has SHA-256", e);
    }
  }
}
