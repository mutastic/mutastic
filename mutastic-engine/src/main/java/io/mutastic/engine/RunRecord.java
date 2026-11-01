package io.mutastic.engine;

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
import java.util.stream.Stream;

/// What a run found, kept for the next run to compare with: each mutant's status and catching test,
/// the score, and a hash of the compiled code and tests.
///
/// The file is one entry per line, tab-separated, so it diffs well: `code <hash>`, `tool <hash>`,
/// `score <score>`, then `<id> <status> <caught by or ->`.
///
/// @param code the hash of every class file in the code and the tests
/// @param tool the hash of the mutastic that ran: a new mutastic can change results too
public record RunRecord(String code, String tool, double score, Map<String, Entry> results) {

  public record Entry(Status status, Optional<String> caughtBy) {}

  public RunRecord {
    results = Map.copyOf(results);
  }

  public static RunRecord of(Project project, String tool, Report report) {
    var results = new LinkedHashMap<String, Entry>();
    report
        .results()
        .forEach(
            result ->
                results.put(result.mutant().id(), new Entry(result.status(), result.caughtBy())));
    return new RunRecord(codeOf(project), tool, report.score(), results);
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
      double score = 0;
      var results = new LinkedHashMap<String, Entry>();
      for (String line : Files.readAllLines(file)) {
        var parts = line.split("\t");
        switch (parts[0]) {
          case "code" -> code = parts[1];
          case "tool" -> tool = parts[1];
          case "score" -> score = Double.parseDouble(parts[1]);
          default ->
              results.put(
                  parts[0],
                  new Entry(
                      Status.valueOf(parts[1]),
                      parts[2].equals("-") ? Optional.empty() : Optional.of(parts[2])));
        }
      }
      return code == null
          ? Optional.empty()
          : Optional.of(new RunRecord(code, tool, score, results));
    } catch (IOException | RuntimeException e) {
      return Optional.empty(); // an unreadable record is no record: the next run writes a new one
    }
  }

  public void write(Path file) {
    var lines = new ArrayList<String>(List.of("code\t" + code, "tool\t" + tool, "score\t" + score));
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
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (Path root :
          Stream.of(project.classes(), project.testClasses()).flatMap(List::stream).toList()) {
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
