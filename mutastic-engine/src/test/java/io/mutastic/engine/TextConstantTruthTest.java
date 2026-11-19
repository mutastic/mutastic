package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.report.SourceView;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/// Each card a changed string or regular expression gets says what its mutant does: see
/// [ClaimFixture].
class TextConstantTruthTest {
  private final ClaimFixture strings =
      new ClaimFixture("text/Strings.java", "mutastic/string-constant");
  private final ClaimFixture regexes =
      new ClaimFixture("text/Regexes.java", "mutastic/regex-constant");
  private static SourceView sources;

  @BeforeAll
  static void compile() throws Exception {
    sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
  }

  @Test
  void aChangedStringIsTheCodeWithThatString() throws Exception {
    assertThat(strings.running().stream().map(ClaimFixture::sourceMethod))
        .as("what's left to run")
        .containsExactlyInAnyOrder(
            "returned",
            "compared",
            "appended",
            "empty",
            "aFormat",
            "aStaticFinal",
            "multiLine",
            "aLongOne",
            "builtAndReturned",
            "namedAsAnError",
            "messageForAHelper",
            "notTheChecksOfALibrary",
            "computedAndReturned");
    assertThat(strings.claimsThatLie()).isEmpty();
  }

  @Test
  void whatOnlyTellsAPersonIsArid() {
    assertThat(strings.changesIn("logged")).containsExactly(arid("seen", "only writes output"));
    assertThat(strings.changesIn("thrown"))
        .containsExactly(arid("the same", "only an exception's message"));
    assertThat(strings.changesIn("built"))
        .containsExactly(arid("built", "only an exception's message"));
    assertThat(strings.changesIn("formattedIntoAnException"))
        .containsExactly(arid("%s and %s", "only an exception's message"));
    assertThat(strings.changesIn("formatIntoAnException"))
        .containsExactly(arid("%s and %s", "only an exception's message"));
    assertThat(strings.changesIn("builtIntoAnException"))
        .containsExactly(arid("in full ", "only an exception's message"));
    assertThat(strings.changesIn("builderStartedIntoAnException"))
        .containsExactly(arid("to start ", "only an exception's message"));
    assertThat(strings.changesIn("checked"))
        .containsExactly(arid("a is needed", "only a failed check's message"));
    assertThat(strings.changesIn("builtForItsCaller"))
        .containsExactly(arid("no match", "only an exception's message"));
    assertThat(strings.changesIn("aSubclassOfOurOwn"))
        .containsExactly(arid("no such", "only an exception's message"));
  }

  /// A log call's values worked out after its format string leave the string a log call's.
  @Test
  void aFormatStringIsAridWhateverComesAfterIt() {
    assertThat(strings.changesIn("timed"))
        .containsExactly(arid("took {0} ns", "only writes output"));
    assertThat(strings.changesIn("severalArguments"))
        .containsExactly(arid("{0} of {1}", "only writes output"));
    assertThat(strings.changesIn("printed"))
        .containsExactly(arid("%s and %d%n", "only writes output"));
    assertThat(strings.changesIn("formattedThenPrinted"))
        .containsExactly(arid("%d chars", "only writes output"));
    assertThat(strings.changesIn("concatenatedAfter"))
        .containsExactly(arid("seen {0}", "only writes output"));
    assertThat(strings.changesIn("chosenAfter"))
        .containsExactly(
            arid("chose {0}", "only writes output"), arid("none", "only writes output"));
    assertThat(strings.changesIn("computedIntoAnException"))
        .containsExactly(arid("%d left", "only an exception's message"));
  }

  /// What javac writes differs by release, and by whether it keeps local names: `"a" + b` is a
  /// builder before Java 9.
  @ParameterizedTest
  @CsvSource({"8, -g", "8, -g:none", "11, -g", "17, -g:none", "21, -g", "25, -g"})
  void aFormatStringIsAridInEveryShapeJavacWrites(String release, String debug) throws Exception {
    var classes =
        Javac.compile(
            List.of(new Javac.Source("shapes.Logged", LOGGED)),
            List.of(),
            "--release",
            release,
            debug);
    var model = ClassFile.of().parse(Objects.requireNonNull(classes.get("shapes.Logged")));

    var found =
        new Mutations(
                ClassFile.of(),
                Policy.NONE.withOperators(List.of("mutastic/string-constant"), List.of()))
            .find(model);

    assertThat(found)
        .filteredOn(mutant -> !mutant.methodName().equals("returned"))
        .extracting(mutant -> mutant.replacement() + " " + mutant.skipped().orElse("counted"))
        .filteredOn(change -> !change.equals(BEFORE_JAVA_9))
        .containsExactlyInAnyOrder(
            "string \"{0} took {1} ms\" to \"\" arid: only writes output",
            "string \"%s and %d%n\" to \"\" arid: only writes output",
            "string \"%d chars\" to \"\" arid: only writes output",
            "string \"seen {0}\" to \"\" arid: only writes output",
            "string \"chose {0}\" to \"\" arid: only writes output",
            "string \"none\" to \"\" arid: only writes output",
            "string \"%d left\" to \"\" arid: only an exception's message");
    assertThat(found)
        .filteredOn(mutant -> mutant.methodName().equals("returned"))
        .singleElement()
        .satisfies(mutant -> assertThat(mutant.skipped()).isEmpty());
  }

  /// Before Java 9, `"!"` in `a + "!" + b` is a constant of its own, on its way to the log.
  private static final String BEFORE_JAVA_9 = "string \"!\" to \"\" arid: only writes output";

  private static final String LOGGED =
      """
      package shapes;

      import java.util.logging.Level;
      import java.util.logging.Logger;

      class Logged {
        private static final Logger LOG = Logger.getLogger("shapes");

        static void timed(String name, long start) {
          LOG.log(Level.INFO, "{0} took {1} ms", new Object[] {name.trim(), System.currentTimeMillis() - start});
        }

        static void printed(String a, int b) {
          System.out.printf("%s and %d%n", a.trim(), b * 2);
        }

        static void formatted(String a, long b) {
          System.out.println(String.format("%d chars", a.length() + b));
        }

        static void concatenated(String a, String b) {
          LOG.log(Level.INFO, "seen {0}", a + "!" + b);
        }

        static void chosen(String a) {
          LOG.log(Level.INFO, "chose {0}", a.isEmpty() ? "none" : a);
        }

        static void thrown(String a) {
          if (a.isEmpty()) {
            throw new IllegalStateException(String.format("%d left", a.length() - 1));
          }
        }

        static String returned(String a, String b) {
          return String.format("%d!", a.length() - b.length());
        }
      }
      """;

  /// One way the string reaches the log, the other a call that keeps it: the mutant stays.
  @Test
  void aStringThatMayGoElsewhereIsCounted() {
    var printStream = ClassDesc.of("java.io.PrintStream");
    var bytes =
        ClassFile.of()
            .build(
                ClassDesc.of("built.Either"),
                type ->
                    type.withMethodBody(
                        "either",
                        MethodTypeDesc.of(
                            ConstantDescs.CD_void, printStream, ConstantDescs.CD_boolean),
                        ClassFile.ACC_STATIC,
                        code -> {
                          var other = code.newLabel();
                          var done = code.newLabel();
                          code.aload(0)
                              .ldc("either")
                              .iload(1)
                              .ifeq(other)
                              .invokevirtual(
                                  printStream,
                                  "println",
                                  MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                              .goto_(done)
                              .labelBinding(other)
                              .invokestatic(
                                  ConstantDescs.CD_String,
                                  "valueOf",
                                  MethodTypeDesc.of(
                                      ConstantDescs.CD_String, ConstantDescs.CD_Object))
                              .pop()
                              .pop()
                              .labelBinding(done)
                              .return_();
                        }));

    assertThat(
            new Mutations(
                    ClassFile.of(),
                    Policy.NONE.withOperators(List.of("mutastic/string-constant"), List.of()))
                .find(ClassFile.of().parse(bytes)))
        .singleElement()
        .satisfies(mutant -> assertThat(mutant.skipped()).isEmpty());
  }

  /// With the class out of sight, as a dependency missing from the classpath is, nothing shows it's
  /// an exception, and the mutant stays.
  @Test
  void anExceptionWhoseSupertypesCantBeFoundIsNoEvidence() {
    var withoutTheFixtures =
        new Mutations(
            ClassFile.of(),
            Policy.NONE.withOperators(List.of("mutastic/string-constant"), List.of()));

    assertThat(withoutTheFixtures.find(strings.model()))
        .filteredOn(mutant -> mutant.methodName().equals("builtForItsCaller"))
        .singleElement()
        .satisfies(mutant -> assertThat(mutant.skipped()).isEmpty());
  }

  /// Guava's checks are known by their full name: only compiled against one here, not its jar.
  @Test
  void aFailedCheckOfTheKnownLibraryIsArid() throws Exception {
    var classes =
        Javac.compile(
            List.of(
                new Javac.Source(
                    "com.google.common.base.Preconditions",
                    """
                    package com.google.common.base;

                    public final class Preconditions {
                      public static void checkArgument(boolean ok, Object message) {}
                    }
                    """),
                new Javac.Source(
                    "guava.Checked",
                    """
                    package guava;

                    class Checked {
                      static void checked(boolean ok) {
                        com.google.common.base.Preconditions.checkArgument(ok, "needed");
                      }
                    }
                    """)),
            List.of(),
            "-g");
    var model = ClassFile.of().parse(Objects.requireNonNull(classes.get("guava.Checked")));

    assertThat(
            new Mutations(
                    ClassFile.of(),
                    Policy.NONE.withOperators(List.of("mutastic/string-constant"), List.of()))
                .find(model))
        .singleElement()
        .satisfies(
            mutant -> assertThat(mutant.skipped()).contains("arid: only a failed check's message"));
  }

  private static String arid(String text, String why) {
    return "string \"%s\" to \"\" (arid: %s)".formatted(text, why);
  }

  @Test
  void whatIsntAnLdcInTheCodeHasNoChange() {
    assertThat(strings.changesIn("concatenated")).isEmpty();
    assertThat(strings.changesIn("annotated")).isEmpty();
  }

  @Test
  void aStringSwitchsOwnTestHasNoChange() {
    assertThat(strings.changesIn("aSwitch")).isEmpty();
  }

  @Test
  void aLabelEscapesWhatItHoldsAndCutsALongStringShort() {
    assertThat(strings.changesIn("multiLine")).containsExactly("string \"one\\ntwo\" to \"\"");
    assertThat(strings.changesIn("aLongOne"))
        .containsExactly("string \"a string of more words than a label sho…\" to \"\"");
  }

  @Test
  void aChangedRegexIsTheCodeWithThatRegex() throws Exception {
    assertThat(regexes.running()).as("the cards checked").hasSizeGreaterThan(18);
    assertThat(regexes.valueClaimsThatLie()).isEmpty();
  }

  @Test
  void anAnchorOnWhatHasToMatchAllOfItIsEquivalent() throws Exception {
    var why = " (" + TextConstants.WHOLE_MATCH + ")";
    assertThat(regexes.changesIn("anchorsThatDoNothing"))
        .containsExactly(
            "regex \"^a+$\" to \"a+$\"" + why,
            "regex \"^a+$\" to \"^a+\"" + why,
            "regex \"^a+$\" to \"^a$\"");
    assertThat(regexes.skippedThatDiffer()).isEmpty();
  }

  @Test
  void onlyARegexGoingStraightIntoACallIsChanged() {
    for (var method :
        List.of("theReplacement", "asTheReceiver", "notARegex", "throughAVariable", "plainText")) {
      assertThat(regexes.changesIn(method)).as(method).isEmpty();
    }
  }

  @Test
  void whatItBecomesAlwaysCompiles() {
    assertThat(TextConstants.regexChanges("\\.+")).containsExactly("\\.");
    assertThat(TextConstants.regexChanges("a\\$")).isEmpty();
    assertThat(TextConstants.regexChanges("(?i)a")).isEmpty();
    assertThat(TextConstants.regexChanges("abc")).isEmpty();
    assertThat(TextConstants.regexChanges("[a+]")).containsExactly("[^a+]");
    assertThat(TextConstants.regexChanges("[]a]")).containsExactly("[^]a]");
    assertThat(TextConstants.regexChanges("a{2,3}?b")).containsExactly("ab");
    assertThat(TextConstants.regexChanges("\\w\\D")).containsExactly("\\W\\D", "\\w\\d");
  }

  @Test
  void aRegexInAStaticFieldIsChangedAsTheClassLoads() throws Exception {
    var mutant = inMethod("<clinit>");
    assertThat(mutant.replacement()).isEqualTo("regex \"^b+\" to \"b+\"");
    assertThat(ClaimFixture.outcome(regexes.mutated(mutant), "inAField", "ab", "x"))
        .isEqualTo("ax");
    assertThat(regexes.outcomeOfOriginal("inAField", "ab", "x")).isEqualTo("ab");
    assertThat(sources.advice(mutant).headline())
        .isEqualTo("no test tells `^b+` from `b+` in a static field or initialiser");
  }

  @Test
  void aRegexInAnInstanceFieldIsChangedInTheConstructor() throws Exception {
    var mutant = inMethod("<init>");
    assertThat(mutant.replacement()).isEqualTo("regex \"^a\" to \"a\"");
    assertThat(ClaimFixture.outcome(regexes.mutated(mutant), "perInstance", "ba", "x"))
        .isEqualTo("bx");
    assertThat(regexes.outcomeOfOriginal("perInstance", "ba", "x")).isEqualTo("ba");
    assertThat(sources.advice(mutant).headline())
        .isEqualTo("no test tells `^a` from `a` in a field or constructor");
  }

  @Test
  void otherOperatorsStillLeaveInitialisersAlone() throws Exception {
    assertThat(strings.mutants())
        .extracting(Mutant::methodName)
        .doesNotContain("<clinit>", "<init>");
  }

  @Test
  void aCardNamesTheTextAndAsksForATestOfIt() {
    var string = sources.advice(onlyString("compared"));
    assertThat(string.change()).isEqualTo("the string `\"a\"` becomes `\"\"`");
    assertThat(string.headline()).isEqualTo("no test tells `\"a\"` from `\"\"` in `compared()`");
    assertThat(string.fix()).isEqualTo("add a test whose result depends on `\"a\"`, and assert it");
    var regex =
        sources.advice(
            regexes.running().stream()
                .filter(mutant -> mutant.methodName().equals("aClass"))
                .findFirst()
                .orElseThrow());
    assertThat(regex.change()).isEqualTo("the regular expression `[ab]` becomes `[^ab]`");
    assertThat(regex.headline()).isEqualTo("no test tells `[ab]` from `[^ab]` in `aClass()`");
    assertThat(regex.fix())
        .isEqualTo("add a test with text only one of `[ab]` and `[^ab]` matches, and assert it");
  }

  private Mutant onlyString(String method) {
    return strings.running().stream()
        .filter(mutant -> mutant.methodName().equals(method))
        .findFirst()
        .orElseThrow();
  }

  private Mutant inMethod(String method) {
    return regexes.running().stream()
        .filter(mutant -> mutant.methodName().equals(method))
        .findFirst()
        .orElseThrow();
  }
}
