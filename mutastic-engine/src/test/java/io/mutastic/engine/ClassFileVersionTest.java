package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A class file from a newer JDK than mutastic runs on is refused by name, with the JDK that wrote
/// it and what to do, not as the ClassFile API's bare "Unsupported class file version". JDK 25
/// reads up to version 69, so JDK 26's 70 and JDK 27's 71 were an internal error.
class ClassFileVersionTest {
  @TempDir Path work;

  @Test
  void aClassFileThisJdkReadsIsRead() throws Exception {
    var file = Files.write(work.resolve("Own.class"), ownBytes());

    assertThat(ClassFileVersion.parse(ClassFile.of(), file).thisClass().asInternalName())
        .isEqualTo("io/mutastic/engine/ClassFileVersionTest");
  }

  @Test
  void aNewerJdksClassFileIsRefusedSayingWhichJdk() throws Exception {
    int latest = ClassFile.latestMajorVersion();
    var bytes = ownBytes();
    bytes[6] = (byte) ((latest + 1) >> 8);
    bytes[7] = (byte) (latest + 1);
    var file = Files.write(work.resolve("Pricing.class"), bytes);
    int jdk = latest + 1 - 44;

    assertThatThrownBy(() -> ClassFileVersion.parse(ClassFile.of(), file))
        .isInstanceOf(Engine.ClassFileTooNew.class)
        .hasMessage(
            ("%s is from JDK %d (class file version %d), and mutastic is running on JDK %d, which"
                    + " reads class files up to JDK %d. Run mutastic on JDK %d or later: install"
                    + " one, or point MUTASTIC_JAVA_HOME at it, or name one to the build plugin (Gradle's javaLauncher,"
                    + " Maven's -Dmutastic.jdk)")
                .formatted(
                    shown(file), jdk, latest + 1, Runtime.version().feature(), jdk - 1, jdk));
  }

  /// Not a class file at all: left to the ClassFile API to say so.
  @Test
  void bytesWithNoClassFileHeaderAreNotJudged() {
    assertThatCode(() -> ClassFileVersion.readable(work.resolve("empty.class"), new byte[] {1, 2}))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                ClassFileVersion.readable(
                    work.resolve("odd.class"), new byte[] {0, 0, 0, 0, 0, 0, 0x7f, 0x7f}))
        .doesNotThrowAnyException();
  }

  private static Path shown(Path file) {
    var here = Path.of("").toAbsolutePath();
    return file.startsWith(here) ? here.relativize(file) : file;
  }

  private static byte[] ownBytes() throws Exception {
    try (var in =
        Objects.requireNonNull(
            ClassFileVersionTest.class.getResourceAsStream("ClassFileVersionTest.class"))) {
      return in.readAllBytes();
    }
  }
}
