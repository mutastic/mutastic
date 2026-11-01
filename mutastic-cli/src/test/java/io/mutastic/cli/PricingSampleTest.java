package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// End to end on `samples/pricing`, a JDK 17 Gradle project: Gradle reads the setup, the engine
/// runs on 25 and the tests on 17, and the loop an agent follows closes a gap.
class PricingSampleTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  @TempDir
  Path work;

  @Test
  void findsTheMissedEdgeAndVerifiesTheFix() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.PASSED);
    assertThat(run.out()).contains("\"reason\":\"run-finished\",\"jdk\":17");
    assertThat(run.out()).contains("\"Killed\":6,\"Survived\":1,\"Timeout\":0,\"NoCoverage\":0,\"Ignored\":2");
    assertThat(run.out()).contains("\"statusReason\":\"equivalent: already returns the default\"");
    var missed = run.out().lines().filter(line -> line.contains("\"status\":\"Survived\"")).findFirst().orElseThrow();
    assertThat(missed).contains("\"line\":17").contains("\"original\":\">=\",\"replacement\":\">\"");
    var id = missed.replaceAll(".*\"id\":\"(m[0-9a-f]{8})\".*", "$1");

    assertThat(Samples.mutastic("verify", id, project.toString()).exit()).isEqualTo(Main.FAILED);

    // What the advice asks for: a test at the edge, and no production change.
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    Files.writeString(test, Files.readString(test).replaceFirst("\\}\\s*$", """

          @Test
          void anOrderOfExactlyOneHundredGetsTheDiscount() {
            assertEquals(10, new Pricing().discount(100));
          }
        }
        """));

    var verified = Samples.mutastic("verify", id, project.toString());
    assertThat(verified.exit()).isEqualTo(Main.PASSED);
    assertThat(verified.out()).contains(id + " is caught by com.example.pricing.PricingTest#anOrderOfExactlyOneHundredGetsTheDiscount");
  }

  @Test
  void agentTextLeadsWithTheVerdict() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("run", project.toString(), "--format=agent");

    assertThat(run.out().lines().findFirst().orElseThrow())
        .isEqualTo("mutastic: PASS · score 85.7% (threshold 80) · 1 missed, 0 not covered, 6 caught of 7 mutants (2 more skipped)");
    assertThat(run.out()).contains("   - if (total >= 100) {", "   + if (total > 100) {");
  }

  @Test
  void theDefaultOffATerminalIsOneLinePerMissAndASummary() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("run", project.toString());

    assertThat(run.out()).startsWith("✗ src/main/java/com/example/pricing/Pricing.java:17 m855c8e3c no test checks the edge");
    assertThat(run.out().strip().lines().filter(line -> line.contains("Score")).findFirst().orElseThrow())
        .isEqualTo("    Score  85.7%  threshold 80 ✓");
    // Progress goes to stderr, as counter lines, so stdout is only the result.
    assertThat(run.err()).contains("mutastic dev · reading the Gradle build…", "coverage  3 tests on JDK 17", "[9/9]");
  }

  @Test
  void aBrokenBuildIsAUsageErrorNotACrash() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    java.nio.file.Files.writeString(project.resolve("build.gradle.kts"), "this is not kotlin");

    var run = Samples.mutastic("run", project.toString());

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err()).contains("Gradle couldn't build");
  }
}
