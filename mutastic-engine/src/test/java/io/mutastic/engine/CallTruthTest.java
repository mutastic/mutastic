package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.report.SourceView;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Each card a change to a call gets says what its mutant does: see [ClaimFixture].
class CallTruthTest {
  @TempDir static Path temp;

  private static ClaimFixture omitted;

  @BeforeAll
  static void compile() throws Exception {
    omitted = new ClaimFixture(temp, "calls/Omitted.java", "mutastic/omit-call");
  }

  @Test
  void anOmittedCallIsLeftOutAndWhatItWasGivenGoesOn() throws Exception {
    assertThat(omitted.running()).as("the cards checked").hasSizeGreaterThan(9);
    assertThat(omitted.claimsThatLie()).isEmpty();
  }

  @Test
  void anOmittedCallKeepsWhatTheCallCanOnlyReturn() {
    assertThat(omitted.changesIn("trimmed")).containsExactly("keep receiver");
    assertThat(omitted.changesIn("aBuilderStep")).containsExactly("keep receiver");
    assertThat(omitted.changesIn("anAbsoluteInt")).containsExactly("keep argument");
    assertThat(omitted.changesIn("aStaticWrapper")).containsExactly("keep argument");
    assertThat(omitted.changesIn("anInstanceWrapper")).containsExactly("keep argument");
  }

  @Test
  void onlyCallsItCanBeSureOfAreOmitted() {
    assertThat(omitted.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain(
            "discarded",
            "callsAGenericMethod",
            "callsABoundedGenericMethod",
            "twoToChooseFrom",
            "aLibrarysMethod",
            "aLong");
  }

  @Test
  void anOmittedCallsCardSaysWhatGoesOnInItsPlace() {
    var sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
    for (var mutant : omitted.running()) {
      var called = "`%s()`".formatted(CallRule.calledName(mutant.original()));
      var kept =
          mutant.replacement().equals(Wrappers.KEEP_RECEIVER)
              ? "what it's called on"
              : "what it's given";
      var advice = sources.advice(mutant);
      assertThat(advice.change())
          .as(ClaimFixture.describe(mutant))
          .isEqualTo("%s is left out, so %s goes on in its place".formatted(called, kept));
      assertThat(advice.headline()).contains("no test needs " + called);
      assertThat(advice.fix()).contains(called);
    }
  }
}
