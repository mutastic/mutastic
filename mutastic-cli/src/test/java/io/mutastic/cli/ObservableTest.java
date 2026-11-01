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
}
