package io.mutastic.worker;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/// The project's libraries, as its build's test class path lists them: jars, and directories
/// (another module's classes, the copy of its resources). [SystemLoader] defines their classes, so
/// a library that looks a class up by its own loader finds the project's too, as it does in the
/// build.
///
/// A jar is read as the JDK's class path reads it: at the running JDK's version when it's
/// multi-release, with each class's signers when it's signed, and a signed entry that doesn't match
/// its signature is refused (`SecurityException`), never defined unsigned.
final class Libraries {
  /// The system property the engine passes them in, separated as a class path is.
  static final String PROPERTY = "mutastic.libraries";

  private final List<Path> directories;
  private final List<Jar> jars;

  private Libraries(List<Path> directories, List<Jar> jars) {
    this.directories = List.copyOf(directories);
    this.jars = List.copyOf(jars);
  }

  /// Each entry of `classPath`, in order; none for an empty one.
  static Libraries of(String classPath) {
    var directories = new ArrayList<Path>();
    var jars = new ArrayList<Jar>();
    for (var entry : entries(classPath)) {
      var path = Path.of(entry);
      if (Files.isDirectory(path)) {
        directories.add(path);
      } else if (Files.isRegularFile(path)) {
        jars.add(Jar.open(path));
      }
    }
    return new Libraries(directories, jars);
  }

  static List<String> entries(String classPath) {
    return classPath.isEmpty()
        ? List.of()
        : Arrays.stream(classPath.split(File.pathSeparator, -1))
            .filter(entry -> !entry.isEmpty())
            .toList();
  }

  /// A class's bytes, where they came from, and the manifest of its jar, if it's in one.
  static final class Found {
    private final byte[] bytes;
    private final CodeSource source;
    private final @Nullable Manifest manifest;
    private final String entry;

    Found(byte[] bytes, CodeSource source, @Nullable Manifest manifest, String entry) {
      this.bytes = bytes;
      this.source = source;
      this.manifest = manifest;
      this.entry = entry;
    }

    byte[] bytes() {
      return bytes;
    }

    CodeSource source() {
      return source;
    }

    @Nullable Manifest manifest() {
      return manifest;
    }

    /// Its path in the jar or directory: `com/example/Name.class`.
    String entry() {
      return entry;
    }
  }

  /// The class `name` from the first library that has it, or null if none does.
  @Nullable Found classFile(String name) throws IOException {
    var entry = name.replace('.', '/') + ".class";
    for (var directory : directories) {
      var file = directory.resolve(entry);
      if (Files.isRegularFile(file)) {
        var source = new CodeSource(url(directory), (CodeSigner[]) null);
        return new Found(Files.readAllBytes(file), source, null, entry);
      }
    }
    for (var jar : jars) {
      var found = jar.classFile(entry);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /// Every library's resource `name`, in order.
  List<URL> resources(String name) {
    var found = new ArrayList<URL>();
    for (var directory : directories) {
      if (Files.exists(directory.resolve(name))) {
        found.add(url(directory.resolve(name)));
      }
    }
    for (var jar : jars) {
      if (jar.file().getJarEntry(name) != null) {
        found.add(jar.resource(name));
      }
    }
    return found;
  }

  private static URL url(Path path) {
    try {
      return path.toUri().toURL();
    } catch (MalformedURLException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// One jar, open for the worker's life.
  private record Jar(Path path, JarFile file, URL location) {
    static Jar open(Path path) {
      try {
        var file = new JarFile(path.toFile(), true, ZipFile.OPEN_READ, Runtime.version());
        return new Jar(path, file, url(path));
      } catch (IOException e) {
        throw new UncheckedIOException("couldn't open the library " + path, e);
      }
    }

    @Nullable Found classFile(String entry) throws IOException {
      var found = file.getJarEntry(entry);
      if (found == null) {
        return null;
      }
      byte[] bytes;
      try (var in = file.getInputStream(found)) {
        bytes = in.readAllBytes();
      }
      // Known only once the entry has been read through, and checked against its signature then.
      var source = new CodeSource(location, found.getCodeSigners());
      return new Found(bytes, source, file.getManifest(), entry);
    }

    URL resource(String name) {
      try {
        return URI.create("jar:" + path.toUri() + "!/" + name).toURL();
      } catch (MalformedURLException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /// `name`'s attribute in its section of `manifest`, else in the main section, else null.
  static @Nullable String attribute(
      @Nullable Manifest manifest, String section, Attributes.Name name) {
    if (manifest == null) {
      return null;
    }
    var own = manifest.getAttributes(section);
    var value = own == null ? null : own.getValue(name);
    return value != null ? value : manifest.getMainAttributes().getValue(name);
  }
}
