package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Project;
import io.mutastic.api.TestSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemServicesTest {
  @TempDir Path project;

  /// A test that installs a `System.LoggerFinder` passes in the build and fails under mutastic:
  /// when the tests fail, the message says why, and how to run without them.
  @Test
  void aFailureNamesTheServiceTheJdkCantFindHere() throws Exception {
    var testResources = project.resolve("src/test/resources");
    var services = Files.createDirectories(testResources.resolve("META-INF/services"));
    Files.writeString(services.resolve("java.lang.System$LoggerFinder"), "a.RecordingFinder\n");
    Files.writeString(services.resolve("org.example.NotTheJdks"), "a.Other\n");

    var provided = SystemServices.providedBy(withTestResources(testResources));
    assertThat(provided).containsExactly("java.lang.System$LoggerFinder");

    var failed =
        new Engine.BaselineFailed(
            List.of("com.example.LoggingTest#warns", "com.example.LoggingTest#quiet"),
            new TestWorker.Baseline(false, List.of(), java.util.Map.of(), java.util.Map.of()),
            Path.of("worker.log"),
            provided);
    assertThat(failed.getMessage())
        .contains("the project provides java.lang.System$LoggerFinder in META-INF/services")
        .contains("mutastic loads the project in class loaders of its own")
        .contains("To run without those tests: --exclude-tests=LoggingTest\n")
        .endsWith("Their output is in worker.log.");
  }

  @Test
  void withoutSuchAServiceTheFailureIsTheTests() {
    assertThat(SystemServices.providedBy(withTestResources(project))).isEmpty();
    assertThat(
            new Engine.BaselineFailed(
                    List.of("a.BTest#c"),
                    new TestWorker.Baseline(
                        false,
                        List.of("a.BTest#c"),
                        java.util.Map.of(),
                        java.util.Map.of("a.BTest#c", "expected: <24> but was: <25>")),
                    Path.of("worker.log"),
                    List.of())
                .endingIn("./gradlew test --tests 'a.BTest.c'")
                .getMessage())
        .isEqualTo(
            """
            a.BTest#c fails without any mutant, so mutastic can't judge one.
            It failed: expected: <24> but was: <25>
            Its output is in worker.log. To run it alone:
              ./gradlew test --tests 'a.BTest.c'""");
  }

  private static Project withTestResources(Path testResources) {
    return new Project(
        "rates",
        Path.of("java"),
        List.of(),
        Path.of("."),
        List.of(),
        List.of(),
        List.of(),
        List.of(testResources),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        TestSelection.ALL,
        Map.of());
  }
}
