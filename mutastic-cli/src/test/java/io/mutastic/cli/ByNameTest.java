package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A module whose dependency in the same build loads the module's own class by name
/// (`samples/by-name`, where `:shapes` finds `:fast`'s `FastArea` with `Class.forName`): its tests
/// pass in the build, where every module is on one classpath, so they pass under mutastic too.
class ByNameTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  /// `side < 0` always false in `FastArea.of()`, which `squares` catches, once `Area.fastest()` has
  /// found `FastArea`.
  private static final String CAUGHT =
      "\"id\":\"m01f17486\",\"fingerprint\":\"01f17486790786cc\",\"status\":\"Killed\"";

  @TempDir Path work;

  @Test
  void gradle() throws Exception {
    assertTheLookupFindsIt(Samples.copy(SAMPLES.resolve("by-name"), work.resolve("by-name")));
  }

  @Test
  void maven() throws Exception {
    assertTheLookupFindsIt(
        Samples.copy(SAMPLES.resolve("maven-by-name"), work.resolve("maven-by-name")));
  }

  private static void assertTheLookupFindsIt(Path project) {
    var run = Samples.mutastic("run", project.toString(), ":fast", "--format=jsonl");

    assertThat(run.exit()).as(run.err()).isNotEqualTo(Main.TESTS_FAIL);
    assertThat(run.out()).as(run.err()).contains(CAUGHT);
  }
}
