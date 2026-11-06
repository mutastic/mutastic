package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.Mutator;
import io.mutastic.api.Operator;
import io.mutastic.api.Policy;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The four sets `--operators` takes, on a project whose rules file adds an operator in each tier:
/// `stronger` adds to the defaults, `extended` stands alone and is added to another set, and `all`
/// is every operator.
class OperatorSetTest {
  private static final List<Mutator> ADDED =
      List.copyOf(
          CallRules.parse(
              rule("acme/plain", "defaults")
                  + rule("acme/strong", "stronger")
                  + rule("acme/modern", "extended"),
              "acme.yaml"));

  @Test
  void defaultsIsTheDefaultsTier() {
    assertThat(ids(List.of("defaults")))
        .contains("acme/plain", "mutastic/remove-conditional")
        .doesNotContain("acme/strong", "acme/modern", "mutastic/negate-conditional");
    assertThat(ids(List.of())).isEqualTo(ids(List.of("defaults")));
  }

  @Test
  void strongerIsTheDefaultsAndTheStrongerTier() {
    assertThat(ids(List.of("stronger")))
        .contains("acme/plain", "acme/strong", "mutastic/negate-conditional")
        .doesNotContain("acme/modern");
  }

  @Test
  void extendedIsItsOwnTierAloneAndAddsToAnotherSet() {
    assertThat(ids(List.of("extended"))).containsExactly("acme/modern");
    assertThat(ids(List.of("defaults", "extended")))
        .contains("acme/plain", "acme/modern")
        .doesNotContain("acme/strong");
    assertThat(ids(List.of("stronger", "extended"))).isEqualTo(ids(List.of("all")));
  }

  @Test
  void allIsEveryOperatorAndCanLeaveOneOut() {
    assertThat(ids(List.of("all")))
        .isEqualTo(OperatorSet.available(ADDED).stream().map(Operator::id).toList());
    assertThat(ids(List.of("all", "-extended"))).isEqualTo(ids(List.of("stronger")));
  }

  @Test
  void aWordThatNamesNothingListsTheSetsFirst() {
    assertThatThrownBy(() -> ids(List.of("everything")))
        .hasMessageStartingWith(
            "no operator or set is called everything; there are defaults, stronger, extended,"
                + " all, acme,");
  }

  private static List<String> ids(List<String> chosen) {
    return OperatorSet.active(Policy.NONE.withOperators(chosen, ADDED)).stream()
        .map(Operator::id)
        .toList();
  }

  private static String rule(String id, String tier) {
    return """
        - id: %s
          explanation: A call is removed.
          tier: %s
          owners: java.lang.String
          methods: trim
          rewrite: remove
          headline: no test checks what `.{name}()` does in {method}
          help: assert the trimmed text
        """
        .formatted(id, tier);
  }
}
