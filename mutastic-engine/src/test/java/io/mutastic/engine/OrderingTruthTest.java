package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.ClaimFixture.Inputs;
import io.mutastic.report.SourceView;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Each card an order turned round gets says what its mutant does: see [ClaimFixture].
class OrderingTruthTest {
  private final ClaimFixture orderings =
      new ClaimFixture("library/Orderings.java", "library/reverse-ordering", Inputs.TEXTS);

  @Test
  void anOrderTurnedRoundIsWhatTheCardSays() throws Exception {
    assertThat(orderings.running()).as("the cards checked").hasSizeGreaterThan(11);
    assertThat(orderings.claimsThatLie()).isEmpty();
  }

  @Test
  void eachWayOfOrderingHasItsChange() {
    assertThat(orderings.changesIn("compareTo")).containsExactly("swap operands");
    assertThat(orderings.changesIn("ofAnyComparable")).containsExactly("swap operands");
    assertThat(orderings.changesIn("reversedList")).containsExactly("remove");
    assertThat(orderings.changesIn("reverseOrderOf")).containsExactly("keep argument");
    assertThat(orderings.changesIn("reverseOrder")).containsExactly("naturalOrder");
    assertThat(orderings.changesIn("reverseOrderOfCollections")).containsExactly("naturalOrder");
  }

  @Test
  void aCompareToWhoseArgumentIsOfASupertypeIsLeftAlone() {
    assertThat(orderings.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain("aDateComparedWithASupertype");
  }

  @Test
  void aResultOnlyTestedForZeroIsSkippedAsEquivalent() throws Exception {
    var why =
        " (equivalent: `a.compareTo(b)` and `b.compareTo(a)` are `0` for the same values, and"
            + " only that is tested)";
    assertThat(orderings.changesIn("same")).containsExactly("swap operands" + why);
    assertThat(orderings.changesIn("different")).containsExactly("swap operands" + why);
    assertThat(orderings.skippedThatDiffer()).isEmpty();
  }

  @Test
  void anOrderTurnedRoundsCardAsksForValuesOutOfOrder() {
    var sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
    for (var mutant : orderings.running()) {
      var advice = sources.advice(mutant);
      assertThat(advice.headline()).as(ClaimFixture.describe(mutant)).startsWith("no test ");
      assertThat(advice.fix())
          .isEqualTo("use values out of order, and assert the order that comes out");
    }
  }
}
