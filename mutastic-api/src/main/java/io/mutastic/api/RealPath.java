package io.mutastic.api;

import java.io.IOException;
import java.nio.file.Path;

/// A path as the file system has it, so two names for one directory compare equal. On macOS `/var`
/// and `/tmp` are links into `/private`: a build tool reports `/private/var/…` for a directory the
/// person typed as `/var/…`, and a plain comparison says one isn't inside the other.
@Internal
public final class RealPath {
  private RealPath() {}

  /// `path`, absolute, with its links resolved: the longest part of it that's there resolved, and
  /// the rest, not made yet, as it is.
  public static Path of(Path path) {
    var absolute = path.toAbsolutePath().normalize();
    for (Path at = absolute; at != null; at = at.getParent()) {
      try {
        return at.toRealPath().resolve(at.relativize(absolute));
      } catch (IOException notThere) {
        // Not there yet: resolve what's above it.
      }
    }
    return absolute;
  }
}
