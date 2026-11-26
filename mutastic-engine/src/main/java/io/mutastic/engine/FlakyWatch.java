package io.mutastic.engine;

import io.mutastic.api.LikelyFlaky;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import io.mutastic.api.WatchedTest;
import io.mutastic.worker.TestNames;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/// The tests watched since a run named them likely flaky (decision 27), kept beside the run records
/// in `last-runs/flaky-watch.tsv`, so whatever keeps those between runs (a CI cache) keeps it too:
/// one test per line, `<test>\t<runs held>`, sorted by test.
///
/// Only a full run, or the `--from-shards` merge, changes it. A share reads it and confirms against
/// it, but shares may run side by side on one work directory, and each sees only part of a test's
/// catches, so none writes it.
public final class FlakyWatch {
  /// Its name in `last-runs/`, which [RunRecords] passes over: it isn't a run's record.
  public static final String FILE = "flaky-watch.tsv";

  private final Path file;

  /// The watch of the module whose work directory is `work`.
  public FlakyWatch(Path work) {
    file = work.resolve("last-runs").resolve(FILE);
  }

  /// Each test watched, with its runs held; none when there's no file, or it can't be read. A line
  /// that isn't a test and a count is passed over.
  public SortedMap<String, Integer> read() {
    var watched = new TreeMap<String, Integer>();
    if (!Files.isRegularFile(file)) {
      return watched;
    }
    try {
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        int tab = line.lastIndexOf('\t');
        if (tab <= 0) {
          continue;
        }
        try {
          watched.put(
              line.substring(0, tab), Math.max(0, Integer.parseInt(line.substring(tab + 1))));
        } catch (NumberFormatException e) {
          // not a count: not a line this file holds
        }
      }
    } catch (IOException e) {
      return new TreeMap<>();
    }
    return watched;
  }

  /// Keeps `watched`, or removes the file when there's none to watch.
  public void write(Map<String, Integer> watched) {
    try {
      if (watched.isEmpty()) {
        Files.deleteIfExists(file);
        return;
      }
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          new TreeMap<>(watched)
              .entrySet().stream()
                  .map(entry -> entry.getKey() + "\t" + entry.getValue() + "\n")
                  .collect(Collectors.joining()),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// The watch after a full run, and what became of each test it watched. A test the run named
  /// starts again at 0 runs held. One it didn't that caught a mutant held, as each catch it made
  /// repeated [WatchedTest#CONFIRMATIONS] times, and adds 1; at [WatchedTest#NEEDED] it's
  /// forgotten. One that caught nothing stays as it was, as does one the run left out
  /// (`--exclude-tests`), which isn't said either: the run says nothing of it.
  public static After after(Map<String, Integer> before, Report report, List<String> leftOut) {
    var next = new TreeMap<>(before);
    var watched = new ArrayList<WatchedTest>();
    before.forEach(
        (test, held) -> {
          if (report.likelyFlaky(test).isPresent() || leftOut(test, leftOut)) {
            return;
          }
          if (!caughtSomething(report, test)) {
            watched.add(new WatchedTest(test, held, false));
            return;
          }
          int now = held + 1;
          boolean released = now >= WatchedTest.NEEDED;
          if (released) {
            next.remove(test);
          } else {
            next.put(test, now);
          }
          watched.add(new WatchedTest(test, now, released));
        });
    report.likelyFlaky().stream().map(LikelyFlaky::test).forEach(test -> next.put(test, 0));
    return new After(next, watched);
  }

  /// What a run that doesn't change the watch says of it: each test it watched, as it was. A test
  /// the run named is said in its likely-flaky line instead, and one it left out not at all.
  public static List<WatchedTest> unchanged(
      Map<String, Integer> watch, Report report, List<String> leftOut) {
    return watch.entrySet().stream()
        .filter(entry -> report.likelyFlaky(entry.getKey()).isEmpty())
        .filter(entry -> !leftOut(entry.getKey(), leftOut))
        .map(entry -> new WatchedTest(entry.getKey(), entry.getValue(), false))
        .toList();
  }

  /// The watch to keep, and the tests it watched as this run left them.
  public record After(SortedMap<String, Integer> watch, List<WatchedTest> watched) {}

  /// Whether a run leaving out `names` (`--exclude-tests`, in any form [TestNames] takes) leaves
  /// out `test`, `com.example.PricingTest#bigOrders(int)`.
  static boolean leftOut(String test, List<String> names) {
    return names.stream().anyMatch(name -> TestNames.leavesOut(name, test));
  }

  private static boolean caughtSomething(Report report, String test) {
    return report.results().stream()
        .anyMatch(
            result ->
                result.status() == Status.CAUGHT
                    && result.caughtBy().filter(test::equals).isPresent());
  }
}
