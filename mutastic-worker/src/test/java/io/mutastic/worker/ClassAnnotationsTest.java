package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.worker.fixture.CounterTest;
import io.mutastic.worker.fixture.TestApp;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ClassAnnotationsTest {
  @Test
  void readsTheClasssOwnAnnotationsPastEveryKindOfElement() throws Exception {
    assertThat(ClassAnnotations.of(classFile(TestApp.class)))
        .containsExactly(
            "io/mutastic/worker/fixture/Settings", "io/mutastic/worker/fixture/Configured");
  }

  @Test
  void aClassWithoutAnnotationsHasNone() throws Exception {
    assertThat(ClassAnnotations.of(classFile(CounterTest.class))).isEmpty();
  }

  private static byte[] classFile(Class<?> type) throws Exception {
    var root = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    return Objects.requireNonNull(
        RootsLoader.classFile(List.of(root), type.getName()), "the fixture's class file");
  }
}
