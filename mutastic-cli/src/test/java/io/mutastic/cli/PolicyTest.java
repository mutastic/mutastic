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

  /// A filter can skip one mutant, not its whole method: the change events' calls are skipped with
  /// its reason, and every other mutant still runs.
  @Test
  void aPluginFilterSkipsOneMutantAtATime() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("observable"));
    plugin(project.resolve("plugins/quiet.jar"), "acme.NoAuditCalls", guideExample());
    Files.writeString(
        project.resolve("mutastic.yaml"),
        "plugins:\n  - plugins/quiet.jar\nfilters:\n  - acme:no-audit-calls\n");

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");

    assertThat(run.err()).doesNotContain("mutastic:");
    var mutants = run.out().lines().filter(line -> line.contains("mutant-finished")).toList();
    assertThat(mutants)
        .filteredOn(line -> line.contains("firePropertyChange"))
        .hasSize(2)
        .allMatch(line -> line.contains("\"statusReason\":\"arid: only records an audit event\""));
    assertThat(mutants)
        .filteredOn(line -> !line.contains("firePropertyChange"))
        .isNotEmpty()
        .noneMatch(line -> line.contains("only records an audit event"))
        .anyMatch(line -> !line.contains("statusReason"));
  }

  /// A jar with one filter, as a team would ship its own: it skips listener registration.
  private static void plugin(Path jar) throws Exception {
    plugin(jar, "acme.NoListeners", NO_LISTENERS);
  }

  private static final String NO_LISTENERS =
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
      """;

  /// The guide's `NoAuditCalls`, from the first line of its code to its last, on the sample's
  /// change events in place of an audit log.
  private static String guideExample() throws java.io.IOException {
    var guide =
        Files.readString(Path.of(System.getProperty("mutastic.docs"), "guides", "plugins.mdx"));
    var start = guide.indexOf("package acme;", guide.indexOf("## Filters"));
    return guide
        .substring(start, guide.indexOf("```", start))
        .replace("com/acme/Audit", "java/beans/PropertyChangeSupport")
        .replace("\"record\"", "\"firePropertyChange\"");
  }

  /// A jar with the filter `className`, written as `source`.
  private static void plugin(Path jar, String className, String source) throws Exception {
    var sources = Files.createTempDirectory("plugin");
    var path = className.replace('.', '/');
    var file = sources.resolve(path + ".java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, source);
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
                file.toString());
    assertThat(compiled).isZero();
    Files.createDirectories(jar.getParent());
    try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry(path + ".class"));
      out.write(Files.readAllBytes(sources.resolve(path + ".class")));
      out.putNextEntry(new JarEntry("META-INF/services/io.mutastic.api.Filter"));
      out.write((className + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }
}
