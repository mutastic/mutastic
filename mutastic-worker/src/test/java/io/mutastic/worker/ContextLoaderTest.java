package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.worker.fixture.Marker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ContextLoaderTest {
  private static final String MARKER = Marker.class.getName();

  /// A run's loader, as a TestLoader is: its own copy of `Marker`, defined from the same bytes.
  private static final class Run extends ClassLoader {
    Run() {
      super(ContextLoaderTest.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.equals(MARKER)) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        var loaded = findLoadedClass(name);
        if (loaded != null) {
          return loaded;
        }
        try (var in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
          var bytes = in.readAllBytes();
          return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }

    @Override
    public @Nullable URL getResource(String name) {
      return name.equals("only-this-run") ? getParent().getResource(MARKER_FILE) : null;
    }
  }

  private static final String MARKER_FILE = MARKER.replace('.', '/') + ".class";

  @Test
  void aClassIsTheCurrentRunsAndNeverTheLastOnes() throws Exception {
    var context = new ContextLoader(ClassLoader.getSystemClassLoader());
    var first = new Run();
    var second = new Run();

    context.runWith(first);
    var inTheFirst = context.loadClass(MARKER);
    context.runWith(second);
    var inTheSecond = context.loadClass(MARKER);

    assertThat(inTheFirst.getClassLoader()).isSameAs(first);
    assertThat(inTheSecond.getClassLoader()).isSameAs(second);
    assertThat(inTheSecond).isNotSameAs(inTheFirst);
    assertThat(Class.forName(MARKER, false, context)).isSameAs(inTheSecond);
  }

  @Test
  void itDefinesNothingItself() throws Exception {
    var context = new ContextLoader(ClassLoader.getSystemClassLoader());
    context.runWith(new Run());

    assertThat(context.loadClass(MARKER).getClassLoader()).isNotSameAs(context);
  }

  @Test
  void aResourceIsTheCurrentRunsToo() {
    var context = new ContextLoader(ClassLoader.getSystemClassLoader());
    context.runWith(new Run());

    assertThat(context.getResource("only-this-run")).isNotNull();
    assertThat(context.getResource(MARKER_FILE)).isNull();
  }
}
