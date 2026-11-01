package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.worker.fixture.CounterTest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductionLoaderTest {
  private static final String COUNTER = "io.mutastic.worker.fixture.CounterTest";

  /// `@MethodSource("com.example.UtilTest#cases")` names a test class in a string, which renaming
  /// can't reach: JUnit loads it by its original name through the test copy's loader.
  @Test
  void aSplitTestNamedInAStringLoadsByItsOriginalName() throws Exception {
    var production =
        new ProductionLoader(
            List.of(), Map.of(), roots(), Set.of(COUNTER), ClassLoader.getPlatformClassLoader());
    var copy = Class.forName(Renamer.renamed(COUNTER, COUNTER, 4), false, production);

    var byOriginalName = Class.forName(COUNTER, false, copy.getClassLoader());

    assertThat(byOriginalName.getName()).isEqualTo(COUNTER);
    assertThat(byOriginalName.getClassLoader()).isSameAs(production);
    assertThat(byOriginalName).isNotSameAs(copy);
  }

  /// Spring reads a test class's bytecode as a resource to find its `@Import`s, by the copy's name.
  @Test
  void aRenamedCopysBytecodeIsAResource() throws Exception {
    var production =
        new ProductionLoader(
            List.of(), Map.of(), roots(), Set.of(COUNTER), ClassLoader.getPlatformClassLoader());
    var copy = Renamer.renamed(COUNTER, COUNTER, 4);

    try (var in = production.getResourceAsStream(copy.replace('.', '/') + ".class")) {
      assertThat(in).isNotNull();
      assertThat(in.readAllBytes()).isEqualTo(production.bytesFor(copy));
    }
  }

  private static List<Path> roots() throws Exception {
    return List.of(
        Path.of(CounterTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
  }
}
