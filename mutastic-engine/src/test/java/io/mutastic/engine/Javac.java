package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/// javac for tests that compile a fixture, as the truth tests do once for each claim a card makes.
/// Mutation testing runs those tests again for every mutant they reach, so the compiling is kept
/// apart from what's being checked: all of it happens in memory, in one task, and a class is
/// compiled only the first time its source text is seen.
///
/// The text is the only thing kept against. javac's output for a text can't depend on the code
/// under test: a mutant that changes a card's words changes the text, and that text is compiled
/// afresh. So the cards, the mutants and their bytes are still made anew on every run, and only
/// what javac would give again is not.
///
/// Mutastic loads a test's classes afresh for each test, so a static field would keep nothing from
/// one test to the next: what's compiled is kept in a directory under `java.io.tmpdir`, which is
/// the worker's own and goes when it does.
final class Javac {
  private static final Path KEPT =
      Path.of(System.getProperty("java.io.tmpdir"), "mutastic-test-javac");

  private Javac() {}

  /// A source file, `shapes/Shapes.java` as the class `shapes.Shapes`, with its text.
  record Source(String className, String text) {
    static Source read(Path file, String className) throws IOException {
      return new Source(className, Files.readString(file));
    }

    private String path() {
      return className.replace('.', '/') + ".java";
    }
  }

  /// A name for a class of this text: the name given, and a digest of the text. The same claim is
  /// then the same class, whatever other claims come before it, and is compiled once.
  static String nameFor(String prefix, String text) {
    return prefix + "_" + digest(List.of(text)).substring(0, 20);
  }

  /// The classes of `sources`, by name, each with its nested classes, compiled with `options` where
  /// they haven't been already. `alongside` are there for them to refer to, and aren't among the
  /// classes returned.
  static Map<String, byte[]> compile(
      List<Source> sources, List<Source> alongside, String... options) throws IOException {
    var compiled = new HashMap<String, byte[]>();
    var missing = new LinkedHashMap<Source, Path>();
    for (var source : sources) {
      var kept = keptFile(source, alongside, options);
      var classes = read(kept);
      if (classes.isPresent()) {
        compiled.putAll(classes.get());
      } else {
        missing.put(source, kept);
      }
    }
    if (!missing.isEmpty()) {
      var fresh = run(List.copyOf(missing.keySet()), alongside, options);
      for (var each : missing.entrySet()) {
        var classes = fresh.getOrDefault(each.getKey().path(), Map.of());
        write(each.getValue(), classes);
        compiled.putAll(classes);
      }
    }
    return compiled;
  }

  /// A loader of the classes given, from their bytes, whose parent is the test's own.
  static ClassLoader loader(Map<String, byte[]> classes) {
    return new ClassLoader(Javac.class.getClassLoader()) {
      @Override
      protected Class<?> findClass(String name) throws ClassNotFoundException {
        var bytes = classes.get(name);
        if (bytes == null) {
          throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
      }
    };
  }

  private static Path keptFile(Source source, List<Source> alongside, String... options) {
    var parts = new ArrayList<>(List.of(System.getProperty("java.vm.version"), source.className()));
    parts.addAll(List.of(options));
    parts.add(source.text());
    alongside.forEach(other -> parts.add(other.text()));
    return KEPT.resolve(digest(parts));
  }

  private static String digest(List<String> parts) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (var part : parts) {
        digest.update(part.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /// The classes kept in `file`, if they're kept yet.
  private static Optional<Map<String, byte[]>> read(Path file) throws IOException {
    byte[] kept;
    try {
      kept = Files.readAllBytes(file);
    } catch (NoSuchFileException e) {
      return Optional.empty();
    }
    var classes = new HashMap<String, byte[]>();
    try (var in = new DataInputStream(new java.io.ByteArrayInputStream(kept))) {
      for (int left = in.readInt(); left > 0; left--) {
        var name = in.readUTF();
        classes.put(name, in.readNBytes(in.readInt()));
      }
    }
    return Optional.of(classes);
  }

  /// Written whole and moved into place: another worker may be keeping the same classes.
  private static void write(Path file, Map<String, byte[]> classes) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(classes.size());
      for (var entry : classes.entrySet()) {
        out.writeUTF(entry.getKey());
        out.writeInt(entry.getValue().length);
        out.write(entry.getValue());
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

  /// One javac task over the sources, in memory: the classes of each source file, by its path.
  private static Map<String, Map<String, byte[]>> run(
      List<Source> sources, List<Source> alongside, String... options) {
    var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    var units = new ArrayList<JavaFileObject>();
    sources.forEach(source -> units.add(new InMemorySource(source)));
    alongside.forEach(source -> units.add(new InMemorySource(source)));
    var args = new ArrayList<>(List.of(options));
    if (args.isEmpty()) {
      args.add("-g");
    }
    try (var files = new InMemoryClasses(compiler)) {
      var ok = compiler.getTask(null, files, diagnostics, args, null, units).call();
      assertThat(ok).as("javac: %s", diagnostics.getDiagnostics()).isTrue();
      return files.bySource;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final class InMemorySource extends SimpleJavaFileObject {
    private final String text;

    InMemorySource(Source source) {
      super(URI.create("mem:///" + source.path()), Kind.SOURCE);
      this.text = source.text();
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }

  /// Keeps what javac writes, by the source file it came from.
  private static final class InMemoryClasses extends ForwardingJavaFileManager<JavaFileManager> {
    final Map<String, Map<String, byte[]>> bySource = new HashMap<>();

    InMemoryClasses(JavaCompiler compiler) {
      super(compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8));
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
        Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
      if (location != StandardLocation.CLASS_OUTPUT) {
        throw new IllegalArgumentException("javac asked to write to " + location);
      }
      var path = sibling.toUri().getPath().substring(1);
      var classes = bySource.computeIfAbsent(path, ignored -> new HashMap<>());
      return new SimpleJavaFileObject(
          URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
        @Override
        public OutputStream openOutputStream() {
          return new ByteArrayOutputStream() {
            @Override
            public void close() {
              classes.put(className, toByteArray());
            }
          };
        }
      };
    }
  }
}
