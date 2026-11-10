package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A library that looks the module's resources up with its own class loader, a class whose static
/// initialiser fails as a test expects, and a module with no tests and no JUnit
/// (`samples/library-resources`): each runs as the build's own test run does. One run of each
/// module, shared.
class LibraryResourcesTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "library-resources");

  @TempDir static Path work;

  private static Samples.Result app;
  private static Samples.Result labels;

  @BeforeAll
  static void runTheSample() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("library-resources"));
    app = Samples.mutastic("run", project.toString(), ":app", "--format=jsonl", "--report=none");
    labels =
        Samples.mutastic("run", project.toString(), ":labels", "--format=jsonl", "--report=none");
  }

  /// `ResourceBundle.getBundle` in `:labels` finds `:app`'s messages, as under `gradle test`; and
  /// `Settings`, which can't be initialised, doesn't stop the run.
  @Test
  void aLibraryFindsTheModulesResources() {
    assertThat(app.err()).doesNotContain("the tests fail without any mutant");
    assertThat(app.out())
        .contains("\"reason\":\"run-finished\"")
        .containsPattern("\"status\":\"Killed\"[^\\n]*com\\.example\\.app\\.GreetingTest#");
  }

  /// No tests: every mutant is not covered, and the run finishes.
  @Test
  void aModuleWithNoTestsFinishes() {
    assertThat(labels.err()).doesNotContain("internal error");
    assertThat(labels.out())
        .contains("\"reason\":\"run-finished\"")
        .contains("\"status\":\"NoCoverage\"")
        .doesNotContain("\"status\":\"Killed\"");
  }
}
