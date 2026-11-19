package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Which test classes run a `main` class of the project in a JVM of their own, by the bytecode: a
/// call that starts a process, and the class named.
class SeparateJvmsTest {
  @TempDir Path tests;

  private static final String MAIN =
      """
      package app;
      public class Main {
        public static void main(String[] args) {}
      }
      """;

  private static final String NOT_MAIN =
      """
      package app;
      public class Tool {
        public void main(String[] args) {}
      }
      """;

  @Test
  void aTestThatStartsAProcessNamingAMainClassRunsIt() throws Exception {
    var runs =
        found(
            Map.of(
                "app.MainTest",
                """
                package app;
                class MainTest {
                  void runs() throws Exception {
                    new ProcessBuilder("java", "-cp", "x", Main.class.getName()).start();
                  }
                }
                """,
                "app.ByNameTest",
                """
                package app;
                class ByNameTest {
                  void runs() throws Exception {
                    Runtime.getRuntime().exec(new String[] {"java", "app.Main"});
                  }
                }
                """));

    assertThat(runs)
        .containsExactly(
            new SeparateJvms.Run("app.ByNameTest", "app.Main"),
            new SeparateJvms.Run("app.MainTest", "app.Main"));
    assertThat(runs.getFirst().note())
        .isEqualTo(
            "ByNameTest runs app.Main in a separate JVM, and mutants don't reach that JVM. So a"
                + " test there can't catch a mutant in Main, and code only it runs reads as"
                + " reached by no test. To check Main, call what its main calls from a test in the"
                + " tests' own JVM");
  }

  @Test
  void aTestThatNamesMainAndAHelperThatStartsTheProcessRunsIt() throws Exception {
    var runs =
        found(
            Map.of(
                "app.Cli",
                """
                package app;
                class Cli {
                  static void run(String main) throws Exception {
                    new ProcessBuilder("java", main).start();
                  }
                }
                """,
                "app.CliTest",
                """
                package app;
                class CliTest {
                  class Nested {
                    void runs() throws Exception {
                      Cli.run(Main.class.getName());
                    }
                  }
                }
                """));

    assertThat(runs).containsExactly(new SeparateJvms.Run("app.CliTest", "app.Main"));
  }

  @Test
  void aTestThatStartsAnotherProgramOrCallsMainInItsOwnJvmRunsNone() throws Exception {
    var runs =
        found(
            Map.of(
                "app.GitTest",
                """
                package app;
                class GitTest {
                  void runs() throws Exception {
                    new ProcessBuilder("git", "status").start();
                    new Tool().main(new String[0]);
                  }
                }
                """,
                "app.InProcessTest",
                """
                package app;
                class InProcessTest {
                  void runs() {
                    Main.main(new String[0]);
                  }
                }
                """));

    assertThat(runs).isEmpty();
  }

  /// The test classes compiled into `tests`, against the project's two classes, and what they run.
  private List<SeparateJvms.Run> found(Map<String, String> testSources) throws Exception {
    var sources = new java.util.ArrayList<Javac.Source>();
    sources.add(new Javac.Source("app.Main", MAIN));
    sources.add(new Javac.Source("app.Tool", NOT_MAIN));
    testSources.forEach((name, text) -> sources.add(new Javac.Source(name, text)));
    var classes = Javac.compile(sources, List.of());
    var models = new TreeMap<String, ClassModel>();
    for (var each : classes.entrySet()) {
      if (each.getKey().equals("app.Main") || each.getKey().equals("app.Tool")) {
        models.put(each.getKey(), ClassFile.of().parse(each.getValue()));
      } else {
        var file = tests.resolve(each.getKey().replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, each.getValue());
      }
    }
    return SeparateJvms.in(List.of(tests), models);
  }
}
