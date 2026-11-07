package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// What mutastic says of real kotlinc output, checked by running it: one run of the sample, shared,
/// and `verify` where a card's claim is that a test would catch a mutant.
class KotlinShapesTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));
  private static final String INLINES = "src/main/kotlin/com/example/shapes/Inlines.kt";
  private static final String HELPERS = "src/main/kotlin/com/example/shapes/Helpers.kt";

  /// What these tests read of a `mutant-finished` event.
  private record Seen(
      String id, String status, String operator, String file, int line, String method, String why) {
    private static final Pattern EVENT =
        Pattern.compile(
            "\"id\":\"(?<id>[^\"]+)\".*\"status\":\"(?<status>\\w+)\","
                + "\"operator\":\"(?<operator>[^\"]+)\","
                + "\"location\":\\{\"file\":\"(?<file>[^\"]+)\",\"line\":(?<line>\\d+),"
                + ".*\"method\":\"(?<method>[^\"(]+)\\(.*?\\}"
                + ".*?(\"statusReason\":\"(?<why>[^\"]*)\")?,\"(skipKind|coveredBy)\".*");

    static Seen of(String event) {
      var at = EVENT.matcher(event);
      assertThat(at.find()).as(event).isTrue();
      return new Seen(
          at.group("id"),
          at.group("status"),
          at.group("operator"),
          at.group("file"),
          Integer.parseInt(at.group("line")),
          at.group("method"),
          at.group("why") == null ? "" : at.group("why"));
    }
  }

  @TempDir static Path work;

  private static Path project;
  private static List<Seen> mutants;

  @BeforeAll
  static void runTheSample() throws IOException {
    project = Samples.copy(SAMPLES.resolve("kotlin-shapes"), work.resolve("kotlin-shapes"));
    var run = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");
    mutants =
        run.out()
            .lines()
            .filter(event -> event.contains("\"reason\":\"mutant-finished\""))
            .map(Seen::of)
            .toList();
  }

  /// The mutants of `method` the operator made, on this file.
  private static List<Seen> of(String method, String operator, String file) {
    return mutants.stream()
        .filter(mutant -> mutant.method().equals(method))
        .filter(mutant -> mutant.operator().equals(operator))
        .filter(mutant -> mutant.file().equals(file))
        .toList();
  }

  private static Seen only(String method, String operator, String file) {
    var found = of(method, operator, file);
    assertThat(found).as(method + " " + operator + " " + file).hasSize(1);
    return found.getFirst();
  }

  private static int lineOf(String file, String text) throws IOException {
    var lines = Files.readAllLines(project.resolve(file));
    for (int at = 0; at < lines.size(); at++) {
      if (lines.get(at).contains(text)) {
        return at + 1;
      }
    }
    throw new AssertionError(text + " isn't in " + file);
  }

  /// No mutant that runs is on a line its file doesn't have, whatever the class file numbers it.
  @Test
  void everyMutantThatRunsIsOnALineOfItsFile() {
    assertThat(mutants).isNotEmpty();
    for (var mutant : mutants) {
      if (mutant.status().equals("Ignored")) {
        continue;
      }
      var file = project.resolve(mutant.file());
      assertThat(file).as(mutant.toString()).isRegularFile();
      assertThat(mutant.line()).as(mutant.toString()).isBetween(1, lineCount(file));
    }
  }

  private static int lineCount(Path file) {
    try {
      return Files.readAllLines(file).size();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// The loop in `filter` isn't the person's. Their condition in the block they gave it is, though
  /// the two are in one method, and the method's own return.
  @Test
  void whatTheStandardLibraryInlinedIsSkippedAndTheBlockGivenToItIsNot() throws IOException {
    var library =
        mutants.stream()
            .filter(mutant -> mutant.method().equals("evens"))
            .filter(mutant -> mutant.file().startsWith("kotlin/"))
            .toList();
    assertThat(library).isNotEmpty();
    assertThat(library)
        .allSatisfy(
            mutant -> {
              assertThat(mutant.status()).isEqualTo("Ignored");
              assertThat(mutant.why())
                  .startsWith("generated: kotlinc copied this from kotlin.collections.");
            });
    int line = lineOf(INLINES, "fun evens");
    assertThat(of("evens", "mutastic/remove-conditional", INLINES))
        .isNotEmpty()
        .allSatisfy(
            mutant -> {
              assertThat(mutant.status()).isNotEqualTo("Ignored");
              assertThat(mutant.line()).isEqualTo(line);
            });
  }

  /// A copy of the module's inline function is on the function's line, and a test of the call
  /// catches it: the mutant really does change what the call does.
  @Test
  void aCopyOfTheProjectsInlineFunctionIsOnItsLineAndATestOfTheCallCatchesIt() throws Exception {
    int line = lineOf(INLINES, "inline fun twice");
    var copy =
        of("usesTwice", "mutastic/math", INLINES).stream()
            .filter(mutant -> mutant.line() == line)
            .toList();
    assertThat(copy).hasSize(1);
    assertThat(copy.getFirst().status()).isEqualTo("Survived");

    var other = only("usesBump", "mutastic/math", HELPERS);
    assertThat(other.line()).isEqualTo(lineOf(HELPERS, "inline fun bump"));
    assertThat(other.status()).isEqualTo("Survived");

    addTest(
        """
        @Test
        fun theCallsGiveWhatTheyShould() {
            assertEquals(4, usesTwice(1))
            assertEquals(11, usesBump(1))
        }
        """);
    assertVerified(copy.getFirst().id());
    assertVerified(other.id());
  }

  /// An inline function's own body says what's true of it, by who runs it.
  @Test
  void anInlineFunctionsOwnBodyIsJudgedByWhoRunsIt() {
    assertThat(only("twice", "mutastic/math", INLINES).why())
        .startsWith("generated: kotlinc copies an inline function into each call")
        .endsWith("the mutants on its copies are the ones that count");
    assertThat(only("calledFromATest", "mutastic/math", INLINES).why())
        .endsWith("only tests call it, and what's copied into a test isn't changed");
    assertThat(only("neverCalled", "mutastic/math", INLINES).status()).isEqualTo("NoCoverage");
    assertThat(only("calledFromJava", "mutastic/math", INLINES).status()).isEqualTo("Killed");
  }

  private static void addTest(String test) throws IOException {
    var path = project.resolve("src/test/kotlin/com/example/shapes/ShapesTest.kt");
    var source = Files.readString(path);
    Files.writeString(path, source.substring(0, source.lastIndexOf('}')) + test.indent(4) + "}\n");
  }

  private static void assertVerified(String id) {
    var verified = Samples.mutastic("verify", id, project.toString());
    assertThat(verified.exit())
        .as(id + "\n" + verified.out() + verified.err())
        .isEqualTo(Main.PASSED);
  }
}
