package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.report.SourceView;
import java.lang.classfile.ClassFile;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Each card a changed string or regular expression gets says what its mutant does: see
/// [ClaimFixture].
class TextConstantTruthTest {
  private static ClaimFixture strings;
  private static ClaimFixture regexes;
  private static SourceView sources;

  @BeforeAll
  static void compile() throws Exception {
    strings = new ClaimFixture("text/Strings.java", "mutastic/string-constant");
    regexes = new ClaimFixture("text/Regexes.java", "mutastic/regex-constant");
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
            "notTheChecksOfALibrary");
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
    var why = " (equivalent: it has to match all of the text anyway)";
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

  private static Mutant onlyString(String method) {
    return strings.running().stream()
        .filter(mutant -> mutant.methodName().equals(method))
        .findFirst()
        .orElseThrow();
  }

  private static Mutant inMethod(String method) {
    return regexes.running().stream()
        .filter(mutant -> mutant.methodName().equals(method))
        .findFirst()
        .orElseThrow();
  }
}
