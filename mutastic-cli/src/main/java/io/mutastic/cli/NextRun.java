package io.mutastic.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/// What the last `mutastic next` left queued, kept in the module's work directory, so the next one
/// runs only those mutants while the code under test is the same: a test written since can only
/// catch some of them, never make a new one. A change to the code, the operators, the lines in
/// scope or mutastic itself runs everything again.
///
/// @param key what the queued mutants were found under: the code under test's hash, the operators,
///   the scope and the tool, as [#keyOf] makes it
/// @param queued the ids still missed then
record NextRun(String key, Set<String> queued) {
  private static final String FILE = "next.properties";

  NextRun {
    queued = Set.copyOf(queued);
  }

  static String keyOf(String code, String operators, String scope, String tool) {
    return String.join("|", code, operators, scope, tool);
  }

  /// The queue the last `next` left, if it was made under `key`.
  static Optional<NextRun> read(Path work, String key) {
    var file = work.resolve(FILE);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    var properties = new Properties();
    try (var in = Files.newBufferedReader(file)) {
      properties.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var queued = properties.getProperty("queued", "");
    var run =
        new NextRun(
            properties.getProperty("key", ""),
            queued.isEmpty() ? Set.of() : Set.of(queued.split(",")));
    return run.key().equals(key) ? Optional.of(run) : Optional.empty();
  }

  void write(Path work) {
    var properties = new Properties();
    properties.setProperty("key", key);
    properties.setProperty("queued", String.join(",", new TreeSet<>(queued)));
    try (var out = Files.newBufferedWriter(work.resolve(FILE))) {
      properties.store(out, "mutastic next: what the last one left queued");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
