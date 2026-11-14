package io.mutastic.cli;

import static io.mutastic.cli.Samples.mutastic;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `samples/parameterized`: parameterized tests on JUnit 5 (`@CsvSource`, `@ValueSource`,
/// `@MethodSource`) and JUnit 4 (`Parameterized`, on the vintage engine), with rows too weak to
/// catch some mutants. A parameterized method is one test, named by its method, never its case.
class ParameterizedSampleTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));
  private static final Path TESTS = Path.of("src/test/java/com/example/calc");

  private static final Pattern ID = Pattern.compile("\"id\":\"(m[0-9a-f]{8})\"");
  private static final Pattern TEST_CLASS = Pattern.compile("\"testClass\":\"(\\w+)\"");
  private static final Pattern CODE = Pattern.compile("\"code\":\"((?:[^\"\\\\]|\\\\.)*)\"");

  @TempDir Path work;

  /// Tests are named by class and method, with the parameters' types, whichever engine ran them: no
  /// case, display name or unique id. Only the second row of `addsMixed` catches `+` becoming `-`,
  /// and its method is named for it.
  @Test
  void aParameterizedTestIsNamedByItsMethod() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("parameterized"), work.resolve("parameterized"));

    var run = mutastic("run", project.toString(), "--format=jsonl", "--report=none");
    var full = mutastic("run", project.toString(), "--format=full", "--report=none");

    var add =
        run.out()
            .lines()
            .filter(line -> line.contains("\"text\":\"IADD -> ISUB\""))
            .findFirst()
            .orElseThrow();
    assertThat(add)
        .contains("\"caughtBy\":\"com.example.calc.CalculatorTest#addsMixed(int, int, int)\"");
    assertThat(run.out())
        .contains("\"com.example.calc.CalculatorTest#multiplies(int, int, int)\"")
        .contains("\"com.example.calc.SubtractJUnit4Test#subtracts\"")
        .doesNotContain("[engine:")
        .doesNotContain("quoted")
        .doesNotContain("__m");
    assertThat(full.out())
        .contains("inputs seen: a = 1, 2; b = 1")
        .contains("inputs seen: 50, 250 (none is 100)")
        .contains("covered by CalculatorTest#evens(int) (its 1 covering test still passes)")
        .contains("covered by SubtractJUnit4Test#subtracts (its 1 covering test still passes)");
  }

  /// Each suggested test, pasted as it is into the class it names, builds and catches its mutant:
  /// in a class of only parameterized tests, which imports neither `@Test` nor the assertion, and
  /// in a JUnit 4 class, whose test methods must be public or the class doesn't run.
  @Test
  void everySuggestedTestCompilesPastedIntoItsClass() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("parameterized"), work.resolve("parameterized"));

    var suggested = pasted(project, TESTS, ".java", "  ");

    assertThat(suggested.values())
        .anySatisfy(code -> assertThat(code).startsWith("@org.junit.jupiter.api.Test\nvoid "))
        .anySatisfy(
            code ->
                assertThat(code)
                    .contains("org.junit.jupiter.api.Assertions.assertFalse(new Calculator()"))
        .anySatisfy(code -> assertThat(code).startsWith("@Test\npublic void subtractWith"));
    for (var id : suggested.keySet()) {
      var verified = mutastic("verify", id, project.toString());
      assertThat(verified.exit())
          .as(suggested.get(id) + "\n" + verified.out() + verified.err())
          .isEqualTo(Main.PASSED);
    }
  }

  /// The same in Kotlin: a class of only parameterized tests, whose suggestion is pasted as is.
  @Test
  void everySuggestedKotlinTestCompilesPastedIntoItsClass() throws Exception {
    var project =
        Samples.copy(SAMPLES.resolve("parameterized-kotlin"), work.resolve("parameterized-kotlin"));

    var suggested = pasted(project, Path.of("src/test/kotlin/com/example/calc"), ".kt", "    ");

    assertThat(suggested.values())
        .isNotEmpty()
        .allSatisfy(code -> assertThat(code).startsWith("@org.junit.jupiter.api.Test\nfun "));
    for (var id : suggested.keySet()) {
      var verified = mutastic("verify", id, project.toString());
      assertThat(verified.exit())
          .as(suggested.get(id) + "\n" + verified.out() + verified.err())
          .isEqualTo(Main.PASSED);
    }
  }

  /// A JUnit 4 class it can't run, here for a test method that isn't public, fails as a class: it's
  /// named as people write it, why in full, and with a command that runs it alone.
  @Test
  void aJUnit4ClassThatCantRunIsNamedAndRunsAlone() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("parameterized"), work.resolve("parameterized"));
    var test = project.resolve(TESTS).resolve("SubtractJUnit4Test.java");
    var text = Files.readString(test);
    Files.writeString(
        test,
        text.substring(0, text.lastIndexOf('}'))
            + "  @Test\n  void notPublic() {\n    assertEquals(0, new Calculator().subtract(1, 1));"
            + "\n  }\n}\n");

    var red = mutastic("run", project.toString(), "--progress=none");
    var events = mutastic("run", project.toString(), "--format=jsonl");

    assertThat(red.exit()).isEqualTo(Main.TESTS_FAIL);
    assertThat(red.err())
        .contains(
            "mutastic: com.example.calc.SubtractJUnit4Test (the class as a whole: JUnit 4 can't"
                + " run it, so none of its tests ran) fails without any mutant")
        .contains(
            "It failed: Invalid test class 'com.example.calc.SubtractJUnit4Test': 1. Method"
                + " notPublic() should be public")
        .contains("test --tests 'com.example.calc.SubtractJUnit4Test'\n")
        .doesNotContain("[engine:")
        .doesNotContain("__m");
    var failed = events.out().lines().reduce((first, second) -> second).orElseThrow();
    assertThat(failed).contains("\"code\":\"red-baseline\"");
    var command =
        Pattern.compile("\"command\":\"((?:[^\"\\\\]|\\\\.)*)\"")
            .matcher(failed)
            .results()
            .findFirst();
    assertThat(command).isPresent();
    var alone = command.get().group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    // The command is quoted for a POSIX shell, as it's printed there.
    org.junit.jupiter.api.Assumptions.assumeFalse(windows());
    var process =
        new ProcessBuilder(List.of("sh", "-c", alone))
            .directory(Path.of("").toAbsolutePath().toFile())
            .redirectErrorStream(true)
            .start();
    var said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(said).isNotZero();
    assertThat(said)
        .as("the class's own failure, not Gradle's")
        .contains("SubtractJUnit4Test")
        .contains("1 test completed, 1 failed")
        .doesNotContain("out of bounds");
  }

  /// Runs the sample, pastes each missed mutant's suggested test into the class it names, and gives
  /// them by mutant: two mutants with the same call share one test, pasted once.
  private static Map<String, String> pasted(
      Path project, Path tests, String extension, String indent) throws Exception {
    var run = mutastic("run", project.toString(), "--format=jsonl", "--report=none");
    var suggested = new LinkedHashMap<String, String>();
    var byClass = new LinkedHashMap<String, LinkedHashMap<String, String>>();
    for (var line : run.out().lines().toList()) {
      var code = CODE.matcher(line);
      if (!line.contains("\"status\":\"Survived\"") || !code.find()) {
        continue;
      }
      var id = ID.matcher(line);
      var testClass = TEST_CLASS.matcher(line);
      assertThat(id.find() && testClass.find()).as(line).isTrue();
      var test = code.group(1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
      suggested.put(id.group(1), test);
      byClass.computeIfAbsent(testClass.group(1), name -> new LinkedHashMap<>()).put(test, test);
    }
    for (var entry : byClass.entrySet()) {
      var file = project.resolve(tests).resolve(entry.getKey() + extension);
      var text = Files.readString(file);
      var added = new StringBuilder();
      for (var test : entry.getValue().keySet()) {
        added.append('\n').append(test.indent(indent.length()));
      }
      Files.writeString(file, text.substring(0, text.lastIndexOf('}')) + added + "}\n");
    }
    return suggested;
  }

  private static boolean windows() {
    return System.getProperty("os.name").startsWith("Windows");
  }
}
