package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A JavaBean that tells listeners what changed (`samples/observable`): its trivial getters are
/// arid, and a setter that notifies is mutated like any code, so a notification no test listens for
/// is a miss.
class ObservableTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "observable");

  @TempDir Path work;

  @Test
  void gettersAreAridAndAnUnheardNotificationIsMissed() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("observable"));

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");

    assertThat(run.out())
        .contains("arid: trivial getter of name")
        .contains("arid: trivial getter of email")
        .containsPattern(
            "\"status\":\"Killed\"[^\\n]*\"method\":\"setName\\(Ljava/lang/String;\\)V\"[^\\n]*"
                + "firePropertyChange")
        .containsPattern(
            "\"status\":\"Survived\"[^\\n]*\"method\":\"setEmail\\(Ljava/lang/String;\\)V\"[^\\n]*"
                + "firePropertyChange");
  }

  /// An audit runs what the arid filters skip: here the getters. The two the tests read back are
  /// caught, and no test calls the third. Nothing else about the run changes.
  @Test
  void anAuditRunsTheAridMutantsAndSaysWhatHappened() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("observable"));

    var run =
        Samples.mutastic(
            java.util.Map.of("MUTASTIC_ARID", "audit"),
            "run",
            project.toString(),
            "--format=brief",
            "--report=none");

    assertThat(run.out())
        .contains(
            "Arid audit  3 ran anyway: 2 caught (2 by an assertion), 0 missed, 1 not covered;"
                + " checked in Person.getName(), Person.getEmail()")
        .contains("3 skipped (3 arid)");
  }
}
