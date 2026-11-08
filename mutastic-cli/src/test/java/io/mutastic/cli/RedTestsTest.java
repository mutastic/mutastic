package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// A test that failed at a gate check counts as still there when a test of its name is, in any
/// class, or a test with its body: a move or a rename, never both at once.
class RedTestsTest {
  private static final String RATES =
      """
      class RatesTest {
        @Test
        void interestAtOneMillion() {
          assertEquals(40_000, Rates.interest(100_000_000), "{not the end");
        }

        @org.junit.jupiter.api.Test
        void rateAtOneMillion() { assertEquals(400, Rates.rate(100_000_000)); }
      }
      """;

  private static final String INTEREST = "a.RatesTest#interestAtOneMillion";

  @Test
  void aTestIsThereByItsNameInAnyClassOrByItsBody() {
    var body = RedTests.tests(RATES).get("interestAtOneMillion");
    var red = Map.of(INTEREST, body, "a.RatesTest#gone", "0123456789abcdef");

    var moved = found(RATES.replace("class RatesTest", "class InterestTest"));
    var renamed =
        found(
            RATES
                .replace("interestAtOneMillion", "interestAtTheTopTier")
                .replace("assertEquals(40_000,", "assertEquals(\n      40_000,"));
    var both = found(RATES.replace("interestAtOneMillion()", "x()").replace("40_000", "40_001"));

    assertThat(RedTests.gone(red, moved)).containsExactly("a.RatesTest#gone");
    assertThat(RedTests.gone(red, renamed)).containsExactly("a.RatesTest#gone");
    assertThat(RedTests.gone(red, both)).containsExactlyInAnyOrder(INTEREST, "a.RatesTest#gone");
  }

  /// A brace in a string doesn't end a body, and a test's arguments aren't part of its name.
  @Test
  void aBodyEndsAtItsOwnBraceAndATestIsRememberedWithoutItsArguments() {
    int from = RATES.indexOf("interestAtOneMillion()") + "interestAtOneMillion(".length();

    assertThat(RedTests.body(RATES, from)).endsWith("\"{not the end\");\n  }");
    assertThat(RedTests.idOf("a.RatesTest#rate(int)[2]")).isEqualTo("a.RatesTest#rate");
    assertThat(RedTests.idOf("a.RatesTest")).isEqualTo("a.RatesTest");
    assertThat(RedTests.remember(List.of("[engine:junit-jupiter]"), List.of())).isEmpty();
  }

  /// A class that failed as a whole is there while a class of its name is.
  @Test
  void aClassIsThereByItsName() {
    var red = Map.of("a.SetupTest", "");

    assertThat(RedTests.gone(red, found("class SetupTest {}"))).isEmpty();
    assertThat(RedTests.gone(red, found("class OtherTest {}"))).containsExactly("a.SetupTest");
  }

  private static RedTests.Found found(String source) {
    var found = new RedTests.Found(new HashSet<>(), new HashSet<>(), new HashSet<>());
    found.add(source);
    return found;
  }
}
