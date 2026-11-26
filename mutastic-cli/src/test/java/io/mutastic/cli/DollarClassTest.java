package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `samples/dollar-class`: `$Cache`, a class whose name starts with `$`, and `$Cache$Definition`,
/// marked @Generated as Micronaut 4's bean definitions are, all compiled with no SourceFile. Both
/// printed as `com/example/cache/.java` and `.Cache.size()`, the suggested test called
/// `.Cache.size(9)`, which isn't Java, and `mutants` listed and counted the generated class's
/// mutants, which a run skips (corpus survey A9).
class DollarClassTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "dollar-class");

  @TempDir Path work;

  @Test
  void mutantsListsTheCodesOwnByTheirNames() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("dollar-class"));

    var listed = Samples.mutastic(Map.of(), "mutants", project.toString());

    assertThat(listed.exit()).as(listed.err()).isZero();
    assertThat(listed.out())
        .contains(" src/main/java/com/example/cache/$Cache.java:8:")
        .contains(" in $Cache.size()")
        .endsWith("\n4 mutants\n")
        .doesNotContain("Definition")
        .doesNotContain("/.java");
  }

  @Test
  void theSuggestedTestNamesTheClass() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("dollar-class"));

    var run =
        Samples.mutastic(Map.of("MUTASTIC_AGENT", "1"), "run", project.toString(), "--report=none");

    assertThat(run.out())
        .as(run.err())
        .contains("at src/main/java/com/example/cache/$Cache.java:8")
        .contains("assertEquals(9, $Cache.size(9));")
        .doesNotContain(" .Cache.")
        .doesNotContain("/.java");
  }
}
