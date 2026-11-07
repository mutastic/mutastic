package io.mutastic.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/// The run records of a work directory, one for each set of operators, so that a run under another
/// set doesn't leave the next run under the first with nothing to compare with.
///
/// `last-run.tsv` is the latest run, under whatever operators, and is a file older versions of
/// mutastic wrote too. When a run replaces it, it moves to `last-runs/<hash of its operators>.tsv`
/// unless the new run is under the same ones. A file's name is only where to look: what's read is
/// compared by what the record inside says it's of ([RunRecord#comparesWith]), so a stale, renamed
/// or unreadable file is never taken for a run it isn't.
public final class RunRecords {
  /// Records kept beside the latest: sets of operators a project goes back to are few, and each
  /// record is a line per mutant, so a run with one-off sets can't fill the disk. The longest
  /// unused go first.
  static final int KEPT_BESIDE_THE_LATEST = 7;

  private final Path latest;
  private final Path others;

  public RunRecords(Path work) {
    latest = work.resolve("last-run.tsv");
    others = work.resolve("last-runs");
  }

  /// The last run that `record` can be compared with, if there's one.
  public Optional<RunRecord> comparableTo(RunRecord record) {
    return Stream.of(latest, fileFor(record.operators()))
        .flatMap(file -> RunRecord.read(file).stream())
        .filter(record::comparesWith)
        .findFirst();
  }

  /// Every record kept, the latest run first.
  public List<RunRecord> newestFirst() {
    var records = new ArrayList<RunRecord>();
    RunRecord.read(latest).ifPresent(records::add);
    othersOldestFirst().reversed().forEach(file -> RunRecord.read(file).ifPresent(records::add));
    return records;
  }

  /// Makes `record` the latest, keeping the one it replaces if that was under other operators.
  public void keep(RunRecord record) {
    try {
      RunRecord.read(latest)
          .filter(record::sameProjectAs)
          .filter(previous -> !previous.operators().isEmpty())
          .filter(previous -> !previous.operators().equals(record.operators()))
          .ifPresent(this::setAside);
      record.write(latest);
      Files.deleteIfExists(fileFor(record.operators()));
      dropTheOldest();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// Moved rather than written, so the file keeps the time of the run it's of.
  private void setAside(RunRecord previous) {
    try {
      Files.createDirectories(others);
      Files.move(latest, fileFor(previous.operators()), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void dropTheOldest() throws IOException {
    var files = othersOldestFirst();
    for (Path file : files.subList(0, Math.max(0, files.size() - KEPT_BESIDE_THE_LATEST))) {
      Files.deleteIfExists(file);
    }
  }

  private List<Path> othersOldestFirst() {
    if (!Files.isDirectory(others)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(others)) {
      return files
          .filter(file -> file.toString().endsWith(".tsv"))
          .sorted(
              Comparator.comparing(RunRecords::modified).thenComparing(Comparator.naturalOrder()))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static java.nio.file.attribute.FileTime modified(Path file) {
    try {
      return Files.getLastModifiedTime(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// A set's ids can be as long as there are operators, and a file name can't.
  private Path fileFor(String operators) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var hash = digest.digest(operators.getBytes(StandardCharsets.UTF_8));
      return others.resolve(HexFormat.of().formatHex(hash, 0, 8) + ".tsv");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("every JDK has SHA-256", e);
    }
  }
}
