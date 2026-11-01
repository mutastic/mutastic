package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.worker.fixture.CounterTest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TestLoaderTest {
  private static final String COUNTER = "io.mutastic.worker.fixture.CounterTest";

  /// Spring's `@ConditionalOnMissingBean` loads a scanned test `@Configuration` by the name of its
  /// class file: `Class.forName` must give a class of that name, or the JVM refuses it and the
  /// condition can't see the bean.
  @Test
  void aSplitTestByItsOriginalNameIsThatClassAndTheRunGetsItsCopy() throws Exception {
    var roots = roots();
    var production =
        new ProductionLoader(
            List.of(), Map.of(), roots, Set.of(COUNTER), ClassLoader.getPlatformClassLoader());
    var tests = new TestLoader(roots, production, 2);

    assertThat(Class.forName(COUNTER, false, tests).getName()).isEqualTo(COUNTER);
    assertThat(tests.copyOf(COUNTER)).isEqualTo(COUNTER + "__m2");
    assertThat(tests.loadClass(tests.copyOf(COUNTER)).getName()).isEqualTo(COUNTER + "__m2");
    assertThat(tests.copyOf("io.mutastic.worker.fixture.Other"))
        .isEqualTo("io.mutastic.worker.fixture.Other");
  }

  private static List<Path> roots() throws Exception {
    return List.of(
        Path.of(CounterTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
  }
}
