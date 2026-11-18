package io.mutastic.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/// Why tests fail before any mutant when they mock on a JDK newer than their Byte Buddy knows: the
/// tests' JDK, the newest Byte Buddy supports, and the ways out.
///
/// mutastic doesn't set Byte Buddy's `net.bytebuddy.experimental` for the tests itself. The tests
/// would then pass under mutastic and fail in the build, and a mutant judged by a mock that Byte
/// Buddy made from class files it doesn't know is one judged by tests the build doesn't run. A
/// build that wants it can set it in the test task's JVM arguments, which mutastic runs the tests
/// with, as the build does.
final class ByteBuddyJdk {
  private ByteBuddyJdk() {}

  /// Byte Buddy's own words: `Java 25 (69) is not supported by the current version of Byte Buddy
  /// which officially supports Java 22 (66)`.
  private static final Pattern UNSUPPORTED =
      Pattern.compile(
          "Java (?<java>\\d+) \\((?<classFile>\\d+)\\) is not supported by the current version of"
              + " Byte Buddy which officially supports Java (?<supported>\\d+)");

  /// What the tests wrote to `log` from `from` on: this run's output, after earlier runs'.
  record Written(Path log, long from) {}

  /// What to say, from what the tests wrote this run, when Byte Buddy refused their JDK: nothing
  /// when it didn't.
  static String said(Written written) {
    var log = written.log();
    if (!Files.isRegularFile(log)) {
      return "";
    }
    try (var in = Files.newInputStream(log)) {
      in.skipNBytes(Math.min(written.from(), Files.size(log)));
      var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      var found = UNSUPPORTED.matcher(text);
      return found.find() ? said(found.group("java"), found.group("supported")) : "";
    } catch (IOException e) {
      return "";
    }
  }

  /// How much `log` holds so far: none when it isn't there yet.
  static long size(Path log) {
    try {
      return Files.isRegularFile(log) ? Files.size(log) : 0;
    } catch (IOException e) {
      return 0;
    }
  }

  static String said(String java, String supported) {
    return ("\nThis is likely why: the tests run on JDK %s, and their Byte Buddy, which Mockito"
            + " mocks with, supports Java %s at most. Upgrade Mockito, or Byte Buddy if the build"
            + " names it, to a version that supports Java %s; or run the tests on an older JDK,"
            + " with a toolchain in the build, or JAVA_HOME at it.")
        .formatted(java, supported, java);
  }
}
