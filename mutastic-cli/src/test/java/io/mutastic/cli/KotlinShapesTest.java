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
  private static final String COROUTINES = "src/main/kotlin/com/example/shapes/Coroutines.kt";
  private static final String CHECKS = "src/main/kotlin/com/example/shapes/Checks.kt";

  /// What of a coroutine's state a mutant's instruction shows, in what a run says of its change.
  private static final List<String> STATE =
      List.of("throwOnFailure", "IF_ACMP", "to label", "$0", "call to 'resume'", "TABLESWITCH");

  /// What these tests read of a `mutant-finished` event.
  private record Seen(
      String id,
      String status,
      String operator,
      String file,
      int line,
      String method,
      String bytecode,
      String why) {
    private static final Pattern EVENT =
        Pattern.compile(
            "\"id\":\"(?<id>[^\"]+)\".*\"status\":\"(?<status>\\w+)\","
                + "\"operator\":\"(?<operator>[^\"]+)\","
                + "\"location\":\\{\"file\":\"(?<file>[^\"]+)\",\"line\":(?<line>\\d+),"
                + ".*\"method\":\"(?<method>[^\"(]+)\\(.*?\\}"
                + ".*?\"bytecode\":\"(?<bytecode>.*?)\"\\}"
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
          at.group("bytecode"),
          at.group("why") == null ? "" : at.group("why"));
    }
  }

  @TempDir static Path work;

  private static Path project;
  private static List<Seen> mutants;

  @BeforeAll
  static void runTheSample() throws IOException {
    project = Samples.copy(SAMPLES.resolve("kotlin-shapes"), work.resolve("kotlin-shapes"));
    var run =
        Samples.mutastic(
            "run", project.toString(), "--operators=all", "--format=jsonl", "--report=none");
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

  /// The mutant of `method` that isn't skipped, when one that is shares its operator.
  private static Seen theOneRunning(String method, String operator, String file) {
    var found =
        of(method, operator, file).stream()
            .filter(mutant -> !mutant.status().equals("Ignored"))
            .toList();
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

  private static List<Seen> inSuspendFunctions() {
    return mutants.stream()
        .filter(mutant -> mutant.file().equals(COROUTINES))
        .filter(mutant -> !mutant.method().equals("doubles"))
        .toList();
  }

  /// A state machine's checks ask for a test of code nobody wrote: none runs, under any operator.
  @Test
  void aCoroutinesStateIsSkipped() {
    var running =
        inSuspendFunctions().stream().filter(mutant -> !mutant.status().equals("Ignored")).toList();
    assertThat(running).isNotEmpty();
    for (var word : STATE) {
      assertThat(running).as(word).noneMatch(mutant -> mutant.bytecode().contains(word));
    }
    assertThat(inSuspendFunctions())
        .anyMatch(mutant -> mutant.why().startsWith("generated: kotlinc's coroutine state"))
        .noneMatch(mutant -> mutant.status().equals("NoCoverage"));
  }

  /// What the person wrote in those functions stays, and a test of it catches it: the arithmetic,
  /// the loop, the value returned, and a change to the function as a whole, whose first instruction
  /// is the machinery's.
  @Test
  void whatThePersonWroteInACoroutineStaysAndATestCatchesIt() throws Exception {
    var plus = theOneRunning("nextOf", "mutastic/math", COROUTINES);
    assertThat(plus.line()).isEqualTo(lineOf(COROUTINES, "return n + 1"));
    assertThat(plus.status()).isEqualTo("Survived");
    assertThat(theOneRunning("nextOf", "mutastic/replace-body", COROUTINES).status())
        .isNotEqualTo("NoCoverage");
    assertThat(theOneRunning("invokeSuspend", "mutastic/math", COROUTINES).status())
        .isEqualTo("Survived");
    assertThat(of("sumTo", "mutastic/remove-conditional", COROUTINES)).isNotEmpty();
    assertThat(theOneRunning("nextOf", "mutastic/return-value", COROUTINES).status())
        .isNotEqualTo("NoCoverage");

    addTest(
        """
        @Test
        fun theNextOneIsOneMore() {
            assertEquals(2, runBlocking { nextOf(1) })
            assertEquals(listOf(0, 2), doubles(2).toList())
        }
        """);
    assertVerified(plus.id());
    assertVerified(theOneRunning("invokeSuspend", "mutastic/math", COROUTINES).id());
  }

  /// A `lateinit`'s getter and check are kotlinc's. Reading the property is the person's.
  @Test
  void aLateinitsCheckIsSkippedAndWhatReadsItIsNot() {
    var all =
        mutants.stream()
            .filter(mutant -> mutant.method().equals("getName"))
            .filter(mutant -> mutant.file().equals(CHECKS))
            .toList();
    assertThat(all)
        .isNotEmpty()
        .allSatisfy(mutant -> assertThat(mutant.why()).startsWith("generated: "));
    assertThat(only("nameLength", "mutastic/return-value", CHECKS).status()).isEqualTo("Survived");
    assertThat(only("innerLength", "mutastic/return-value", CHECKS).status()).isEqualTo("Survived");
    assertThat(of("innerLength", "mutastic/remove-conditional", CHECKS))
        .isNotEmpty()
        .allSatisfy(
            mutant ->
                assertThat(mutant.why())
                    .isEqualTo(
                        "generated: kotlinc's check that a `lateinit` property has been set"));
  }

  /// `!!` and `as` are the person's: without the check a `null` carries on, and a test of a `null`
  /// catches that. Reading the value straight after throws the same anyway: the sample's test of
  /// `lengthOf(null)` passes with the check there, and with nothing but the read to throw it.
  @Test
  void aNotNullAssertionIsThePersonsUnlessTheNextStepThrowsTheSame() throws Exception {
    var kept = only("keep", "mutastic/remove-void-call", CHECKS);
    var cast = only("asText", "mutastic/remove-void-call", CHECKS);
    assertThat(kept.status()).isEqualTo("Survived");
    assertThat(cast.status()).isEqualTo("Survived");
    for (var method : List.of("lengthOf", "sizeOf", "countOf")) {
      assertThat(only(method, "mutastic/remove-void-call", CHECKS).why())
          .isEqualTo("equivalent: the next step throws the same NullPointerException");
    }

    addTest(
        """
        @Test
        fun aNullIsRefused() {
            assertThrows(NullPointerException::class.java) { keep(null) }
            assertThrows(NullPointerException::class.java) { asText(null) }
        }
        """);
    assertVerified(kept.id());
    assertVerified(cast.id());
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
