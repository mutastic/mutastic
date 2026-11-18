package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Tests that fail because their Byte Buddy doesn't know the JDK they run on are told why.
class ByteBuddyJdkTest {
  @Test
  void byteBuddysRefusalNamesTheCauseAndTheWaysOut(@TempDir Path work) throws Exception {
    var log = work.resolve("worker.log");
    Files.writeString(
        log,
        """
        org.mockito.exceptions.base.MockitoException: Could not modify all classes
        Caused by: java.lang.IllegalArgumentException: Java 25 (69) is not supported by the\
         current version of Byte Buddy which officially supports Java 22 (66) - update Byte Buddy\
         or set net.bytebuddy.experimental as a VM property
        """);

    assertThat(ByteBuddyJdk.said(new ByteBuddyJdk.Written(log, 0)))
        .isEqualTo(
            "\nThis is likely why: the tests run on JDK 25, and their Byte Buddy, which Mockito"
                + " mocks with, supports Java 22 at most. Upgrade Mockito, or Byte Buddy if the"
                + " build names it, to a version that supports Java 25; or run the tests on an"
                + " older JDK, with a toolchain in the build, or JAVA_HOME at it.");
  }

  @Test
  void otherFailuresSayNothingMore(@TempDir Path work) throws Exception {
    var log = work.resolve("worker.log");
    Files.writeString(log, "org.opentest4j.AssertionFailedError: expected: <1> but was: <2>\n");

    assertThat(ByteBuddyJdk.said(new ByteBuddyJdk.Written(log, 0))).isEmpty();
    assertThat(ByteBuddyJdk.said(new ByteBuddyJdk.Written(work.resolve("none.log"), 0))).isEmpty();
  }

  /// The log keeps earlier runs' output: a refusal there, since mended, isn't this run's cause.
  @Test
  void onlyThisRunsOutputCounts(@TempDir Path work) throws Exception {
    var log = work.resolve("worker.log");
    Files.writeString(
        log,
        "Java 25 (69) is not supported by the current version of Byte Buddy which officially"
            + " supports Java 22 (66)\n");
    long from = ByteBuddyJdk.size(log);
    Files.writeString(
        log, "AssertionFailedError: expected: <1>\n", java.nio.file.StandardOpenOption.APPEND);

    assertThat(ByteBuddyJdk.said(new ByteBuddyJdk.Written(log, from))).isEmpty();
    assertThat(ByteBuddyJdk.said(new ByteBuddyJdk.Written(log, 0))).isNotEmpty();
  }
}
