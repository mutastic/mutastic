package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.ClaimFixture.Inputs;
import io.mutastic.report.SourceView;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Each card one end of a collection swapped for the other gets says what its mutant does: see
/// [ClaimFixture].
class EndsTruthTest {
  private final ClaimFixture ends =
      new ClaimFixture("library/Ends.java", "library/swap-ends", Inputs.TEXTS);

  @Test
  void anEndSwappedIsWhatTheCardSays() throws Exception {
    assertThat(ends.running()).as("the cards checked").hasSize(12);
    assertThat(ends.claimsThatLie()).isEmpty();
  }

  @Test
  void anEndSwappedsCardNamesBothEnds() {
    var sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
    for (var mutant : ends.running()) {
      var name = CallRule.calledName(mutant.original());
      var advice = sources.advice(mutant);
      assertThat(advice.change()).isEqualTo(name + " changed to " + mutant.replacement());
      assertThat(advice.fix())
          .isEqualTo(
              "use a collection whose first and last differ, and assert which one comes back");
    }
  }

  /// A rename calls a method that exists: on every type the rule names, each method it renames has
  /// its counterpart with the same parameters.
  @Test
  void everyEndTheRuleSwapsHasItsCounterpartOnEveryType() throws Exception {
    var rule =
        CallRules.BUILT_IN.stream()
            .filter(each -> each.id().equals("library/swap-ends"))
            .findFirst()
            .orElseThrow();
    for (String owner : rule.owners()) {
      var type = Class.forName(owner);
      for (String pair : rule.rewrite().substring("rename ".length()).split(",\\s*", -1)) {
        var names = pair.split("->", 2);
        for (Method method : type.getMethods()) {
          if (method.getName().equals(names[0])) {
            assertThat(hasMethod(type, names[1], method.getParameterTypes()))
                .as("%s.%s for %s", owner, names[1], names[0])
                .isTrue();
          }
        }
      }
    }
  }

  private static boolean hasMethod(Class<?> type, String name, Class<?>[] parameters) {
    return Arrays.stream(type.getMethods())
        .anyMatch(
            method ->
                method.getName().equals(name)
                    && Arrays.equals(method.getParameterTypes(), parameters));
  }
}
