package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.worker.fixture.CounterTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The worker's system class loader, run here directly: the JVM makes it before any agent starts,
/// so the coverage agent never sees it run in a worker.
class SystemLoaderTest {
  private static final String COUNTER = "io.mutastic.worker.fixture.CounterTest";

  @TempDir Path temp;

  /// Before the worker has a production loader there's nothing behind the class path; after, the
  /// JDK's lookups reach the project's classes and resources, and only the project's own.
  @Test
  void findsTheProjectOnceTheWorkerHasLoadedIt() throws Exception {
    var system = new SystemLoader(ClassLoader.getPlatformClassLoader());
    var resource = "io/mutastic/worker/fixture/CounterTest.class";
    SystemLoader.project(null);
    assertThatThrownBy(() -> system.loadClass(COUNTER)).isInstanceOf(ClassNotFoundException.class);
    assertThat(system.getResource(resource)).isNull();
    assertThat(Collections.list(system.getResources(resource))).isEmpty();

    var production =
        new ProductionLoader(
            List.of(),
            Map.of(),
            roots(),
            new TestSplit(Set.of(), Set.of(COUNTER)),
            ClassLoader.getPlatformClassLoader());
    SystemLoader.project(production);
    try {
      assertThat(system.loadClass(COUNTER).getClassLoader()).isSameAs(production);
      assertThat(system.getResource(resource)).isNotNull();
      assertThat(Collections.list(system.getResources(resource))).hasSize(1);
    } finally {
      SystemLoader.project(null);
    }
  }

  /// A `-javaagent` jar the JVM adds: its classes and resources are the system loader's own.
  @Test
  void anAgentsJarIsItsOwn() throws Exception {
    var jar = temp.resolve("agent.jar");
    try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry("io/mutastic/worker/fixture/CounterTest.class"));
      out.write(Files.readAllBytes(roots().get(0).resolve(COUNTER.replace('.', '/') + ".class")));
      out.putNextEntry(new JarEntry("agent.properties"));
      out.write("premain=yes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    var system = new SystemLoader(ClassLoader.getPlatformClassLoader());
    system.appendToClassPathForInstrumentation(jar.toString());

    assertThat(system.loadClass(COUNTER).getClassLoader()).isSameAs(system);
    assertThat(system.getResource("agent.properties")).isNotNull();
    assertThat(Collections.list(system.getResources("agent.properties"))).hasSize(1);
    assertThat(system.getResource("nowhere.properties")).isNull();
  }

  /// A class the project names for a JDK service is the production loader's, by its own name, for
  /// every run: the instance the JDK makes and the class a test reads are the same.
  @Test
  void aServiceClassIsSharedByEveryRun() throws Exception {
    var production =
        new ProductionLoader(
            List.of(),
            Map.of(),
            roots(),
            new TestSplit(Set.of(), Set.of(COUNTER)),
            ClassLoader.getPlatformClassLoader());
    var first = new TestLoader(roots(), production, 1);
    var second = new TestLoader(roots(), production, 2);

    assertThat(first.copyOf(COUNTER)).isEqualTo(COUNTER);
    assertThat(first.loadClass(COUNTER)).isSameAs(second.loadClass(COUNTER));
    assertThat(first.loadClass(COUNTER).getClassLoader()).isSameAs(production);
  }

  /// The providers a project names, comments and blank lines left out; only for the JDK's services.
  @Test
  void readsTheProvidersTheProjectNames() throws IOException {
    var services = Files.createDirectories(temp.resolve("META-INF/services"));
    Files.writeString(
        services.resolve("java.lang.System$LoggerFinder"),
        "# the test's finder\n\ncom.example.RecordingFinder  # records\n");
    Files.writeString(services.resolve("org.example.Plugin"), "com.example.NotTheJdks\n");

    assertThat(TestSplit.providersIn(List.of(temp, temp.resolve("missing"))))
        .containsExactly("com.example.RecordingFinder");
  }

  private static List<Path> roots() throws Exception {
    return List.of(
        Path.of(CounterTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
  }
}
