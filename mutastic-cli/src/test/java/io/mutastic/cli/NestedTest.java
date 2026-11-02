package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Code in every kind of class javac makes from one file (`samples/nested`): static nested, inner,
/// anonymous and local classes, a lambda and enum constant bodies, with `@Nested` tests and an
/// anonymous class in a test. Every mutant is in the file the source has, and named as the source
/// names it, not as javac does.
class NestedTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "nested");
  private static final Pattern FILE = Pattern.compile("\"file\":\"([^\"]+)\"");

  @TempDir Path work;

  @Test
  void eachKindOfClassIsFoundInItsFileAndNamedAsTheSourceNamesIt() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("nested"));

    var events = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");
    var full = Samples.mutastic("run", project.toString(), "--format=full", "--report=none");

    // The @Nested tests ran as the build runs them: 4 at the top, 1 in each nested class.
    assertThat(events.out()).contains("\"tests\":6,");
    assertThat(FILE.matcher(events.out()).results().map(found -> found.group(1)).distinct())
        .containsExactly("src/main/java/com/example/nested/Shipping.java");
    assertThat(events.out())
        .contains("\"class\":\"com.example.nested.Shipping$1\"")
        .contains("\"class\":\"com.example.nested.Shipping$1Sum\"")
        .contains("\"class\":\"com.example.nested.Shipping$Speed$1\"")
        .contains("arid: `toString()` is a debug representation")
        .contains("\"call\":\"new Shipping.Zone(100).surcharge()\"");
    assertThat(full.out())
        .contains("no test checks surcharge() at distance == 100")
        .contains("no test checks heavy() at weight == heavyFrom")
        .contains("tests pass even when - becomes + in compare() in an anonymous class")
        .contains("no test checks a lambda in heavyOnes() at weight == heavyFrom")
        .contains("// in ShippingTest.Zones")
        .contains("Shipping.heavyOnes() (a lambda)")
        .contains("Shipping.compare() (an anonymous class)")
        .doesNotContain("this$0");
  }
}
