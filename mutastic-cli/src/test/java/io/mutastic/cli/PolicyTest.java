package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A project's `mutastic.yaml` over `samples/observable`: a `skip:` rule, a `mutate:` rule that
/// brings back an arid getter, and a filter of the project's own from a plugin jar.
class PolicyTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "observable");

  @TempDir Path work;

  @Test
  void rulesAndANamedPluginFilterDecideWhatRuns() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("observable"));
    plugin(project.resolve("plugins/listeners.jar"));
    Files.writeString(
        project.resolve("mutastic.yaml"),
        """
        rules:
          - skip: com.example.observable.Person#setEmail
            reason: email is checked by the sign-up tests
          - mutate: com.example.observable.Person#getName
            reason: the name is shown everywhere
        plugins:
          - plugins/listeners.jar
        filters:
          - acme:no-listeners
        """);

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");

    assertThat(run.err()).doesNotContain("mutastic:");
    assertThat(run.out())
        .contains(
            "user: email is checked by the sign-up tests (rule `skip: com.example.observable.Person#setEmail`)")
        .contains("user: listeners are wiring")
        .doesNotContain("trivial getter of name");
  }

  /// Finding a plugin's filter isn't running it: only `filters:` does.
  @Test
  void aPluginFilterNotNamedDoesNothing() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("observable"));
    plugin(project.resolve("plugins/listeners.jar"));
    Files.writeString(project.resolve("mutastic.yaml"), "plugins:\n  - plugins/listeners.jar\n");

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");

    assertThat(run.out()).doesNotContain("listeners are wiring");
  }

  /// A jar with one filter, as a team would ship its own: it skips listener registration.
  private static void plugin(Path jar) throws Exception {
    var sources = Files.createTempDirectory("plugin");
    var source = sources.resolve("acme/NoListeners.java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source,
        """
        package acme;

        import io.mutastic.api.Filter;
        import io.mutastic.api.Skip;
        import java.lang.classfile.ClassModel;
        import java.lang.classfile.MethodModel;
        import java.util.Optional;

        public class NoListeners implements Filter {
          public String id() {
            return "acme:no-listeners";
          }

          public Optional<Skip> skip(ClassModel owner, MethodModel method) {
            return method.methodName().stringValue().startsWith("add")
                ? Optional.of(new Skip(Skip.Kind.USER, "listeners are wiring"))
                : Optional.empty();
          }
        }
        """);
    var compiled =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-cp",
                System.getProperty("java.class.path"),
                "-d",
                sources.toString(),
                source.toString());
    assertThat(compiled).isZero();
    Files.createDirectories(jar.getParent());
    try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry("acme/NoListeners.class"));
      out.write(Files.readAllBytes(sources.resolve("acme/NoListeners.class")));
      out.putNextEntry(new JarEntry("META-INF/services/io.mutastic.api.Filter"));
      out.write("acme.NoListeners\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}
