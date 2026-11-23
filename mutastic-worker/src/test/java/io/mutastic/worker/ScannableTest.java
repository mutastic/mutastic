package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.worker.fixture.TestApp;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScannableTest {
  private static final String APP = "io.mutastic.worker.fixture.TestApp";
  private static final String COUNTER = "io.mutastic.worker.fixture.CounterTest";
  private static final Set<String> MARKERS = Set.of("io/mutastic/worker/fixture/Marker");

  /// `TestApp` is annotated with `@Configured`, which is annotated with the marker: as Spring finds
  /// a `@SpringBootConfiguration`, a `@Configuration`, by its `@Component`.
  @Test
  void aClassAnnotatedWithAMarkersAnnotationIsFound() throws Exception {
    var scannable = new Scannable(MARKERS, roots(), ClassLoader.getPlatformClassLoader());

    assertThat(scannable.test(APP)).isTrue();
    assertThat(scannable.test(COUNTER)).isFalse();
    assertThat(scannable.test("io.mutastic.worker.fixture.Missing")).isFalse();
  }

  /// Spring's annotations come from its jars, not the project's directories.
  @Test
  void annotationsComeFromTheLibrariesToo() {
    var scannable = new Scannable(MARKERS, List.of(), ScannableTest.class.getClassLoader());

    assertThat(scannable.test(APP)).isTrue();
  }

  @Test
  void nothingIsFoundWithoutTheMarker() throws Exception {
    var scannable = new Scannable(Scannable.SPRING, roots(), ClassLoader.getPlatformClassLoader());

    assertThat(scannable.test(APP)).isFalse();
  }

  /// A scanned class is shared, never renamed, as a JDK service's provider is.
  @Test
  void aScannedTestClassIsSharedNotRenamed() {
    var split =
        TestSplit.of(
            Set.of(APP, COUNTER, "com.example.Provider"),
            Set.of("com.example.Provider"),
            APP::equals);

    assertThat(split.renamed()).containsExactly(COUNTER);
    assertThat(split.shared()).containsExactlyInAnyOrder(APP, "com.example.Provider");
  }

  /// The tests and the scan reach one class: the production loader's, by its own name, with the
  /// classes nested in it.
  @Test
  void aScannedTestClassIsOneClassForEveryRun() throws Exception {
    var roots = roots();
    var production =
        new ProductionLoader(
            List.of(),
            Map.of(),
            roots,
            new TestSplit(Set.of(COUNTER), Set.of(APP)),
            ClassLoader.getPlatformClassLoader());
    var first = new TestLoader(roots, production, 1);
    var second = new TestLoader(roots, production, 2);

    var app = Class.forName(APP, false, first);
    var inner = Class.forName(APP + "$Inner", false, second);

    assertThat(app.getClassLoader()).isSameAs(production);
    assertThat(Class.forName(APP, false, second)).isSameAs(app);
    assertThat(inner.getClassLoader()).isSameAs(production);
    assertThat(first.copyOf(APP)).isEqualTo(APP);
  }

  private static List<Path> roots() throws Exception {
    return List.of(
        Path.of(TestApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
  }
}
