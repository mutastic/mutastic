package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.function.IntSupplier;
import io.mutastic.worker.fixture.CounterTest;
import org.junit.jupiter.api.Test;

class RenamerTest {
  private static final String COUNTER = "io.mutastic.worker.fixture.CounterTest";
  private static final Set<String> TOP_LEVELS = Set.of("io/mutastic/worker/fixture/CounterTest");

  @Test
  void eachRunsCopyHasItsOwnStatics() throws Exception {
    var loader = new CopiesLoader();
    var first = loader.loadClass(Renamer.renamed(COUNTER, COUNTER, 1));
    var second = loader.loadClass(Renamer.renamed(COUNTER, COUNTER, 2));

    first.getConstructor().newInstance();
    first.getConstructor().newInstance();
    second.getConstructor().newInstance();

    assertThat(first.getName()).isEqualTo("io.mutastic.worker.fixture.CounterTest__m1");
    assertThat(first.getMethod("created").invoke(null)).isEqualTo(2);
    assertThat(second.getMethod("created").invoke(null)).isEqualTo(1);
  }

  @Test
  void nestedClassesFollowTheirRenamedOuterClass() throws Exception {
    var copy = new CopiesLoader().loadClass(Renamer.renamed(COUNTER, COUNTER, 3));
    copy.getConstructor().newInstance();

    var reader = (IntSupplier) copy.getMethod("reader").invoke(null);

    assertThat(reader.getAsInt()).isEqualTo(1);
    assertThat(reader.getClass().getClassLoader()).isSameAs(copy.getClassLoader());
  }

  @Test
  void reportsTheOriginalName() {
    assertThat(Renamer.original("com.example.FooTest__m12$Nested")).isEqualTo("com.example.FooTest$Nested");
    assertThat(Renamer.runOf("com.example.FooTest__m12$Nested")).isEqualTo(12);
    assertThat(Renamer.runOf("com.example.FooTest")).isEqualTo(-1);
  }

  @Test
  void leavesLongerNamesAlone() throws Exception {
    var bytes = RootsLoader.classFile(roots(), COUNTER);
    var renamed = Renamer.rename(bytes, Set.of("io/mutastic/worker/fixture/Counter"), 1);

    assertThat(renamed).isEqualTo(bytes);
  }

  private static java.util.List<java.nio.file.Path> roots() throws Exception {
    var location = CounterTest.class.getProtectionDomain().getCodeSource().getLocation().toURI();
    return java.util.List.of(java.nio.file.Path.of(location));
  }

  /// Defines renamed copies from the test classes, as the production loader does.
  private static final class CopiesLoader extends ClassLoader {
    CopiesLoader() {
      super(RenamerTest.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        var loaded = findLoadedClass(name);
        if (loaded == null && Renamer.runOf(name) >= 0) {
          try {
            var original = RootsLoader.classFile(roots(), Renamer.original(name));
            var bytes = Renamer.rename(original, TOP_LEVELS, Renamer.runOf(name));
            loaded = defineClass(name, bytes, 0, bytes.length);
          } catch (Exception e) {
            throw new ClassNotFoundException(name, e);
          }
        }
        return loaded != null ? loaded : super.loadClass(name, resolve);
      }
    }
  }
}
