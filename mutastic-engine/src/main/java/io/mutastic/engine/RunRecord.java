package io.mutastic.engine;

import io.mutastic.api.Comparison;
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
import java.util.stream.Stream;

/// What a run found, kept for the next run to compare with: each mutant's status and catching test,
/// the score, and a hash of the compiled code and tests.
///
/// The file is one entry per line, tab-separated, so it diffs well: `code <hash>`, `tool <hash>`,
/// `score <score>`, then `<id> <status> <caught by or ->`.
///
/// @param code the hash of every class file in the code and the tests
/// @param tool the hash of the mutastic that ran: a new mutastic can change results too
public record RunRecord(
    String code, String tool, OptionalDouble score, Map<String, Entry> results) {

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
    return new RunRecord(codeOf(roots), tool, report.score(), results);
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

  public static Optional<RunRecord> read(Path file) {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      String code = null;
      String tool = "";
      var score = OptionalDouble.empty();
      var results = new LinkedHashMap<String, Entry>();
      for (String line : Files.readAllLines(file)) {
        var parts = line.split("\t", -1);
        switch (parts[0]) {
          case "code" -> code = parts[1];
          case "tool" -> tool = parts[1];
          case "score" -> score = score(parts[1]);
          default -> results.put(parts[0], entry(parts));
        }
      }
      return code == null
          ? Optional.empty()
          : Optional.of(new RunRecord(code, tool, score, results));
    } catch (IOException | RuntimeException e) {
      return Optional.empty(); // an unreadable record is no record: the next run writes a new one
    }
  }

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
    var lines =
        new ArrayList<String>(
            List.of(
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
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, lines);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// How this run differs from `previous`.
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
