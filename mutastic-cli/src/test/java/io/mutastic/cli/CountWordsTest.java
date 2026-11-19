package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/// A count of one reads as one, as people say it: the gate's pass and the baseline's catches.
class CountWordsTest {
  @Test
  void theGatesPassSaysHowManyTheTestsCatch() {
    assertThat(Main.caught(1, 1)).isEqualTo("catch the only mutant");
    assertThat(Main.caught(0, 1)).isEqualTo("don't catch the only mutant");
    assertThat(Main.caught(6, 6)).isEqualTo("catch all 6 mutants");
    assertThat(Main.caught(4, 6)).isEqualTo("catch 4 of the 6 mutants");
    assertThat(Main.caught(0, 2)).isEqualTo("catch 0 of the 2 mutants");
  }

  @Test
  void theBaselinesCatchesTakeTheirVerb() {
    assertThat(Main.caughtNow(1)).isEqualTo("1 of the baseline's mutants is caught now");
    assertThat(Main.caughtNow(2)).isEqualTo("2 of the baseline's mutants are caught now");
    assertThat(Main.caughtNow(0)).isEqualTo("0 of the baseline's mutants are caught now");
  }
}
