package io.mutastic.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.util.Locale;

/// What to say when a file couldn't be read or written: the disk, its permissions or a file in the
/// way, not mutastic. As GNU's tools say it, the file and the system's own reason, `can't use
/// build/mutastic: Not a directory`, never a Java exception's name.
final class Disk {
  private Disk() {}

  /// `failure` in the person's terms: what couldn't be done, the file, and why.
  static String said(UncheckedIOException failure) {
    var cause = java.util.Objects.requireNonNull(failure.getCause()); // never null, as constructed
    var what = what(failure);
    if (cause instanceof FileSystemException file && file.getFile() != null) {
      var reason = file.getReason() != null ? file.getReason() : reason(file);
      var other = file.getOtherFile() != null ? " (and " + file.getOtherFile() + ")" : "";
      return "%s %s%s: %s%s".formatted(what, shown(file.getFile()), other, reason, advice(reason));
    }
    var message = cause.getMessage();
    var reason = message != null ? message : cause.getClass().getSimpleName();
    return "%s: %s%s"
        .formatted(
            what.equals("can't use") ? "couldn't read or write a file" : what,
            reason,
            advice(reason));
  }

  /// What mutastic was doing, when it said: `couldn't start /usr/bin/java`; else that it couldn't
  /// use the file.
  private static String what(UncheckedIOException failure) {
    var said = failure.getMessage();
    var cause = java.util.Objects.requireNonNull(failure.getCause());
    return said == null || said.equals(cause.toString()) ? "can't use" : said;
  }

  /// The reason a file error without one stands for, from its kind: `AccessDeniedException` is a
  /// permission denied.
  private static String reason(IOException failure) {
    return switch (failure.getClass().getSimpleName()) {
      case "AccessDeniedException" -> "Permission denied";
      case "NoSuchFileException" -> "No such file or directory";
      case "FileAlreadyExistsException" -> "File exists";
      case "NotDirectoryException" -> "Not a directory";
      case "DirectoryNotEmptyException" -> "Directory not empty";
      default -> failure.getClass().getSimpleName();
    };
  }

  /// `file` from where the person is, when it's inside it.
  private static String shown(String file) {
    var here = java.nio.file.Path.of("").toAbsolutePath().normalize();
    var path = java.nio.file.Path.of(file).toAbsolutePath().normalize();
    return path.startsWith(here) && !path.equals(here) ? here.relativize(path).toString() : file;
  }

  /// What to do about the commonest reasons, which are the machine's, not the run's.
  private static String advice(String reason) {
    var lower = reason.toLowerCase(Locale.ROOT);
    if (lower.contains("no space left")) {
      return ". The disk is full: free some space, and run mutastic again";
    }
    if (lower.contains("permission denied") || lower.contains("read-only file system")) {
      return ". mutastic writes in the module's build directory: it needs to be writable";
    }
    if (lower.contains("not a directory") || lower.contains("file exists")) {
      return ". A file is where mutastic keeps a directory: move it, and run mutastic again";
    }
    return "";
  }
}
