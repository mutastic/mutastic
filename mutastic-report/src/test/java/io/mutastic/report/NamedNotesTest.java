package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Status;
import io.mutastic.api.Suggestion;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// A name a card's call is at says what it is, and its value where a covering test shows it.
class NamedNotesTest {
  private static final Advice ADVICE =
      new Advice(
          Optional.of(">="),
          Optional.of(">"),
          "`>=` becomes `>`",
          ">= changed to >",
          "no test checks `isFree()` at `total == freeFrom`",
          List.of("inputs seen: total = 20, 80"),
          "test `isFree()` with `total = freeFrom` and assert the result");

  @Test
  void aFieldMadeAsATestMakesItHasItsValueBesideTheInputs() {
    var named =
        new Suggestion.Named(
            "freeFrom",
            "total == freeFrom",
            Optional.empty(),
            "freeFrom = 50 (Delivery's field, from new Delivery(50))");

    var notes =
        NamedNotes.of(ADVICE, result(named, Optional.of("DeliveryTest#smallOrders"))).notes();

    assertThat(notes)
        .containsExactly(
            "inputs seen: total = 20, 80, with freeFrom = 50 (Delivery's field, from new"
                + " Delivery(50))",
            "none has total == freeFrom");
  }

  @Test
  void aNameWithoutATestsValueSaysWhatItIs() {
    var field =
        new Suggestion.Named(
            "freeFrom",
            "",
            Optional.of("int freeFrom = 20;"),
            "freeFrom (Delivery's field, set by its constructor)");
    var constant =
        new Suggestion.Named(
            "FREE_FROM",
            "FREE_FROM = 100",
            Optional.empty(),
            "FREE_FROM (Discounts.FREE_FROM = 100)");

    assertThat(NamedNotes.of(ADVICE, result(field, Optional.empty())).notes())
        .containsExactly(
            "inputs seen: total = 20, 80",
            "the edge is freeFrom (Delivery's field, set by its constructor)");
    assertThat(NamedNotes.of(ADVICE, result(constant, Optional.empty())).notes())
        .endsWith("the edge is FREE_FROM (Discounts.FREE_FROM = 100)");
    assertThat(
            NamedNotes.of(
                ADVICE, result(new Suggestion.Named("x", "", Optional.empty()), Optional.empty())))
        .isSameAs(ADVICE);
  }

  private static MutantResult result(Suggestion.Named named, Optional<String> builtBy) {
    var mutant =
        new Mutant(
            "com/example/Delivery",
            "isFree",
            "(I)Z",
            0,
            12,
            BuiltIn.BOUNDARY,
            "IF_ICMPLT",
            "IF_ICMPLE",
            Optional.empty());
    var suggestion =
        new Suggestion(
            "new Delivery(50).isFree(50)",
            List.of("total"),
            List.of(50L),
            List.of(new Suggestion.Instance("Delivery", List.of(50L))),
            "true",
            "false",
            Optional.of(named),
            builtBy);
    return new MutantResult(
        mutant,
        Status.MISSED,
        List.of("com.example.DeliveryTest#smallOrders"),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        1,
        Optional.empty(),
        Map.of("total", List.of(20L, 80L)),
        Optional.of(suggestion),
        Duration.ZERO);
  }
}
