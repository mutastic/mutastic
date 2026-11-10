package io.mutastic.worker;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// The engine's copy of the project's resources, on the worker's classpath ahead of the libraries,
/// so a library that looks one up with its own class loader finds it, as on the build's one
/// classpath. The worker's own loaders find each of them in the project's directories already, so a
/// lookup of every resource of a name leaves the copy out: one file twice is two migrations to
/// Flyway, say.
final class ResourceCopy {
  /// Where the copy is; null for none.
  private static volatile @Nullable Path directory;

  private ResourceCopy() {}

  static void at(@Nullable Path copy) {
    directory = copy == null ? null : copy.toAbsolutePath().normalize();
  }

  /// `found` without the copy's.
  static List<URL> without(Collection<URL> found) {
    var kept = new ArrayList<URL>();
    for (var url : found) {
      if (!isCopy(url)) {
        kept.add(url);
      }
    }
    return kept;
  }

  static boolean isCopy(URL url) {
    var copy = directory;
    if (copy == null || !url.getProtocol().equals("file")) {
      return false;
    }
    try {
      return Path.of(url.toURI()).toAbsolutePath().normalize().startsWith(copy);
    } catch (URISyntaxException | IllegalArgumentException e) {
      return false;
    }
  }
}
