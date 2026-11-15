package io.mutastic.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;

/// The project's class files, read: each first checked to be one the JDK mutastic runs on can read.
/// A JDK reads class files only as new as itself, and the ClassFile API refuses a newer one with
/// nothing but its version number.
final class ClassFileVersion {
  /// A JDK's feature version is its class files' major version less this: 69 is JDK 25.
  private static final int JDK_TO_MAJOR = 44;

  private ClassFileVersion() {}

  /// The class in `file`, as `context` reads it.
  static ClassModel parse(ClassFile context, Path file) {
    try {
      var bytes = Files.readAllBytes(file);
      readable(file, bytes);
      return context.parse(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// Refuses a class file newer than this JDK reads, saying which JDK wrote it and what to do.
  static void readable(Path file, byte[] bytes) {
    int major = major(bytes);
    int latest = ClassFile.latestMajorVersion();
    if (major > latest) {
      throw new Engine.ClassFileTooNew(
          ("%s is from JDK %d (class file version %d), and mutastic is running on JDK %d, which"
                  + " reads class files up to JDK %d. Run mutastic on JDK %d or later: point"
                  + " JAVA_HOME at one, or name one to the build plugin (Gradle's javaLauncher,"
                  + " Maven's -Dmutastic.jdk)")
              .formatted(
                  shown(file),
                  major - JDK_TO_MAJOR,
                  major,
                  Runtime.version().feature(),
                  latest - JDK_TO_MAJOR,
                  major - JDK_TO_MAJOR));
    }
  }

  /// `file` from where mutastic runs, when it's inside it.
  private static Path shown(Path file) {
    var here = Path.of("").toAbsolutePath().normalize();
    var absolute = file.toAbsolutePath().normalize();
    return absolute.startsWith(here) ? here.relativize(absolute) : file;
  }

  /// A class file's major version, from its header; 0 when it has no header to read.
  private static int major(byte[] bytes) {
    boolean classFile =
        bytes.length >= 8
            && (bytes[0] & 0xff) == 0xCA
            && (bytes[1] & 0xff) == 0xFE
            && (bytes[2] & 0xff) == 0xBA
            && (bytes[3] & 0xff) == 0xBE;
    return classFile ? ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff) : 0;
  }
}
