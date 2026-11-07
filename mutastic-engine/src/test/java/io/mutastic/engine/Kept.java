package io.mutastic.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// What a fixture's class gives for its inputs, kept for the next time the same class comes. A
/// class a truth test runs is a thousand classes through the JVM over a night: a test's own copies,
/// the claims it compiled, a mutant of the fixture for each. Each makes every later class swap
/// dearer, and very few differ from the run before.
///
/// Like what [Javac] keeps, this can't hide anything: the key holds the class's bytes or its text,
/// and a fixture calls nothing of mutastic's, so nothing a mutant does can change what those bytes
/// do without changing them. What's under test still runs every time: the mutant's bytes are made
/// anew, and only then looked up.
final class Kept {
  private static final Path DIRECTORY = Path.of("build/tmp/mutastic-test-outcomes");

  private Kept() {}

  /// Calls a fixture's methods.
  interface Run {
    List<String> call() throws ReflectiveOperationException;
  }

  /// What `key` names gives, from `run` if it hasn't been seen before. Each part of the key is text
  /// or the hex of some bytes.
  static List<String> outcomes(List<String> key, Run run) throws ReflectiveOperationException {
    var kept = find(key);
    if (kept.isPresent()) {
      return kept.get();
    }
    var outcomes = run.call();
    keep(key, outcomes);
    return outcomes;
  }

  static Optional<List<String>> find(List<String> key) {
    try (var in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file(key))))) {
      var outcomes = new ArrayList<String>();
      for (int left = in.readInt(); left > 0; left--) {
        outcomes.add(in.readUTF());
      }
      return Optional.of(outcomes);
    } catch (NoSuchFileException e) {
      return Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path file(List<String> key) {
    var parts = new ArrayList<>(key);
    parts.add(System.getProperty("java.vm.version"));
    return DIRECTORY.resolve(Javac.digest(parts));
  }

  /// Written whole and moved into place: another worker may be keeping the same thing.
  static void keep(List<String> key, List<String> outcomes) {
    try {
      write(file(key), outcomes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void write(Path file, List<String> outcomes) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(outcomes.size());
      for (var outcome : outcomes) {
        out.writeUTF(outcome);
      }
    }
    Files.createDirectories(file.getParent());
    var written = Files.createTempFile(file.getParent(), "writing", null);
    Files.write(written, bytes.toByteArray());
    try {
      Files.move(written, file, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(written, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
