package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.report.SourceView;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Each card a removed `throw` or a dropped `catch` type gets says what its mutant does: see
/// [ClaimFixture].
class ExceptionTruthTest {
  private static final String FILE = "exceptions/Throws.java";

  private final ClaimFixture thrown = new ClaimFixture(FILE, "mutastic/remove-throw");
  private final ClaimFixture caught = new ClaimFixture(FILE, "mutastic/drop-catch-type");

  @Test
  void aRemovedThrowCarriesOnWithWhatComesNext() throws Exception {
    assertThat(thrown.running()).as("the cards checked").hasSizeGreaterThan(3);
    assertThat(thrown.claimsThatLie()).isEmpty();
  }

  @Test
  void aThrowWithNothingAfterItIsUnviable() {
    assertThat(thrown.changesIn("atTheEnd"))
        .hasSize(1)
        .allMatch(change -> change.contains("(unviable: "));
    assertThat(thrown.changesIn("anElse"))
        .hasSize(1)
        .allMatch(change -> change.contains("(unviable: "));
    assertThat(thrown.changesIn("aRethrow"))
        .hasSize(1)
        .allMatch(change -> change.contains("(unviable: "));
  }

  @Test
  void javacsOwnThrowsHaveNoChange() {
    assertThat(thrown.changesIn("aFinally")).isEmpty();
    assertThat(thrown.changesIn("synchronizedOn")).isEmpty();
    assertThat(thrown.changesIn("aResource")).isEmpty();
    assertThat(thrown.changesIn("exhaustive")).isEmpty();
  }

  @Test
  void anAssertIsArid() {
    assertThat(thrown.changesIn("anAssert"))
        .containsExactly("remove throw AssertionError (arid: only says an assert failed)");
  }

  @Test
  void aDroppedTypeIsTheCatchWithoutIt() throws Exception {
    assertThat(caught.running()).as("the cards checked").hasSize(4);
    assertThat(caught.claimsThatLie()).isEmpty();
  }

  @Test
  void oneCatchCopiedWithItsFinallyIsOneChange() {
    assertThat(caught.changesIn("inAFinally"))
        .containsExactly(
            "no catch of java/lang/NumberFormatException",
            "no catch of java/lang/StringIndexOutOfBoundsException");
  }

  @Test
  void twoCatchesOfOneTypeEachArentChanged() {
    assertThat(caught.changesIn("twoCatches")).isEmpty();
    assertThat(caught.changesIn("aGuard")).isEmpty();
  }

  @Test
  void aCardNamesTheExceptionAndAsksForATestOfIt() {
    var sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
    var removed =
        sources.advice(
            thrown.running().stream()
                .filter(mutant -> mutant.methodName().equals("aGuard"))
                .findFirst()
                .orElseThrow());
    assertThat(removed.change())
        .isEqualTo("the `throw` of `IllegalStateException` is removed, so the code carries on");
    assertThat(removed.headline())
        .isEqualTo("no test needs `aGuard()` to throw `IllegalStateException` here");
    assertThat(removed.fix())
        .isEqualTo("add a test where this throws `IllegalStateException`, and assert that it does");
    var dropped =
        sources.advice(
            caught.running().stream()
                .filter(mutant -> mutant.methodName().equals("twoTypes"))
                .findFirst()
                .orElseThrow());
    assertThat(dropped.change())
        .isEqualTo("this `catch` no longer catches `NumberFormatException`");
    assertThat(dropped.headline())
        .isEqualTo("no test has `NumberFormatException` reach this `catch` in `twoTypes()`");
    assertThat(dropped.fix())
        .isEqualTo(
            "add a test where the `try` throws `NumberFormatException`, and assert what the"
                + " `catch` does");
  }
}
