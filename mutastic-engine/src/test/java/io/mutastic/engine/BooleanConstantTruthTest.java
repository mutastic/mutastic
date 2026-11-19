package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.ClaimFixture.Inputs;
import io.mutastic.report.SourceView;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Each card a `true` or `false` turned round gets says what its mutant does: see [ClaimFixture].
class BooleanConstantTruthTest {
  private final ClaimFixture booleans =
      new ClaimFixture("constants/Booleans.java", "mutastic/boolean-constant", Inputs.NUMBERS);

  @Test
  void aBooleanTurnedRoundIsWhatTheCardSays() throws Exception {
    assertThat(booleans.running()).as("the cards checked").hasSize(7);
    assertThat(booleans.claimsThatLie()).isEmpty();
  }

  @Test
  void onlyABooleanPassedOrStoredIsTurnedRound() {
    assertThat(booleans.changesIn("anArgument")).containsExactly("constant true to false");
    assertThat(booleans.changesIn("aFirstArgument")).containsExactly("constant false to true");
    assertThat(booleans.changesIn("aField")).containsExactly("constant true to false");
    assertThat(booleans.changesIn("aVariable"))
        .containsExactly("constant true to false", "constant false to true");
    assertThat(booleans.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain("returned", "aConditionsValue", "aNumber");
  }

  @Test
  void aVariableNothingReadsIsSkippedAsEquivalent() throws Exception {
    assertThat(booleans.changesIn("neverRead"))
        .containsExactly("constant true to false (equivalent: nothing reads the value it's given)");
    assertThat(booleans.skippedThatDiffer()).isEmpty();
  }

  @Test
  void aBooleanTurnedRoundsCardNamesBothValues() {
    var sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
    for (var mutant : booleans.running()) {
      var advice = sources.advice(mutant);
      assertThat(advice.change()).matches("the constant `(true|false)` becomes `(false|true)`");
      assertThat(advice.headline()).startsWith("no test tells `");
    }
  }
}
