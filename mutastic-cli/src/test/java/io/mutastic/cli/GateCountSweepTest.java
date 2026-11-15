package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The copies counts leave in the temporary directory when they're stopped hard, as when a build's
/// daemon is killed: the next `gate open` removes those older than a day that no running count
/// owns, and nothing else.
class GateCountSweepTest {
  private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
  private static final Instant TWO_DAYS_AGO = NOW.minus(Duration.ofDays(2));

  @TempDir Path temp;

  @Test
  void anOldCopyNoCountOwnsGoes() throws Exception {
    var unowned = copy("mutastic-gate-start1", TWO_DAYS_AGO, null);
    var reusedId = copy("mutastic-gate-start2", TWO_DAYS_AGO, startedEarlier());

    GateCount.sweep(temp, NOW);

    assertThat(unowned).doesNotExist();
    assertThat(reusedId).doesNotExist();
  }

  @Test
  void aNewCopyARunningCountsAndOtherFilesStay() throws Exception {
    var recent = copy("mutastic-gate-start3", NOW.minus(Duration.ofHours(2)), null);
    var running =
        copy("mutastic-gate-start4", TWO_DAYS_AGO, GateCount.owner(ProcessHandle.current()));
    var other = copy("someone-elses", TWO_DAYS_AGO, null);

    GateCount.sweep(temp, NOW);

    assertThat(recent).isDirectory();
    assertThat(running).isDirectory();
    assertThat(other).isDirectory();
  }

  @Test
  void aTemporaryDirectoryThatIsntThereIsLeftAlone() {
    GateCount.sweep(temp.resolve("gone"), NOW);

    assertThat(temp.resolve("gone")).doesNotExist();
  }

  /// This process's id, as if another process had it before: the owner that started then is gone.
  private static Properties startedEarlier() {
    var owner = GateCount.owner(ProcessHandle.current());
    owner.setProperty("started", "1");
    return owner;
  }

  private Path copy(String name, Instant modified, @Nullable Properties owner) throws IOException {
    var copy = Files.createDirectories(temp.resolve(name).resolve("tree/src"));
    Files.writeString(copy.resolve("Pricing.java"), "class Pricing {}\n");
    var top = temp.resolve(name);
    if (owner != null) {
      try (var out = Files.newBufferedWriter(top.resolve("owner.properties"))) {
        owner.store(out, null);
      }
    }
    Files.setLastModifiedTime(top, FileTime.from(modified));
    return top;
  }
}
