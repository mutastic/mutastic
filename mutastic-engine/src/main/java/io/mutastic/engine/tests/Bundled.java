package io.mutastic.engine.tests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.jspecify.annotations.Nullable;

/// A test engine inside mutastic's jar, as a resource beside these classes: on no classpath, where
/// a platform would find it and try to run its tests. Copied out once, the first time a worker
/// needs it.
final class Bundled {
  private final String resource;
  private final String prefix;
  private @Nullable Path copied;

  /// @param resource the jar's name beside these classes, `testng-engine.jar`
  /// @param prefix the start of its copy's name, `mutastic-testng`
  Bundled(String resource, String prefix) {
    this.resource = resource;
    this.prefix = prefix;
  }

  /// The copy, made the first time it's asked for.
  synchronized Path copied() {
    var made = copied;
    if (made != null) {
      return made;
    }
    try (var in = Bundled.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("mutastic's test engines are missing: no " + resource);
      }
      made = Files.createTempFile(prefix, ".jar");
      made.toFile().deleteOnExit();
      Files.copy(in, made, StandardCopyOption.REPLACE_EXISTING);
      copied = made;
      return made;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
