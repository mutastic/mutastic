package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Operator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdviceTest {
  @Test
  void readsAnIfBackToItsSourceOperator() {
    var advice = Advice.of(mutant(BuiltIn.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE", "(I)I"));

    assertThat(advice.before()).contains(">=");
    assertThat(advice.after()).contains(">");
    assertThat(advice.headline()).isEqualTo("no test checks the boundary of `>=` in `discount()`");
  }

  @Test
  void negatesComparisonsAndNullChecks() {
    assertThat(
            Advice.of(mutant(BuiltIn.OTHER_COMPARISON, "IF_ICMPNE", "IF_ICMPEQ", "(I)I")).change())
        .isEqualTo("`==` becomes `!=`");
    assertThat(Advice.of(mutant(BuiltIn.OTHER_COMPARISON, "IFNULL", "IFNONNULL", "(I)I")).change())
        .isEqualTo("`!=` becomes `==`");
    assertThat(Advice.of(mutant(BuiltIn.OTHER_COMPARISON, "IFEQ", "IFNE", "(I)I")).headline())
        .isEqualTo("`discount()` behaves the same with the condition reversed");
  }

  @Test
  void readsAJumpOnABooleanByItsOpcode() {
    // `if (inCredit(b) || ...)`: IFNE into the body, so never jumping is as if it were false.
    assertThat(removed("IFNE", "never jump").change()).isEqualTo("`inCredit(b)` is always false");
    assertThat(removed("IFNE", "always jump").change()).isEqualTo("`inCredit(b)` is always true");
    // `if (inCredit(b))`: IFEQ past the body, javac's usual way round.
    assertThat(removed("IFEQ", "never jump").change()).isEqualTo("`inCredit(b)` is always true");
    assertThat(removed("IFEQ", "never jump").headline())
        .isEqualTo("no test needs `inCredit(b)` to be false in `discount()`");
  }

  @Test
  void saysTheOtherValueATestNeeds() {
    // IFNE never jumping: as if `inCredit(b)` were always false, so a test needs it true.
    assertThat(removed("IFNE", "never jump").headline())
        .isEqualTo("no test needs `inCredit(b)` to be true in `discount()`");
    assertThat(removed("IFNE", "never jump").fix())
        .isEqualTo(
            "add a test where `inCredit(b)` is true, and assert what `discount()` does then");
  }

  /// `if (count != 0)` compiles to IFEQ past the body; `if (count == 0)` to IFNE.
  @Test
  void readsAComparisonWithZeroAsTheSourceWritesIt() {
    var notZero = with(BuiltIn.REMOVE_CONDITIONAL, "IFEQ", "never jump", "count", "0");
    assertThat(Advice.of(notZero).change()).isEqualTo("`count != 0` is always true");
    var zero = with(BuiltIn.REMOVE_CONDITIONAL, "IFNE", "always jump", "count", "0");
    assertThat(Advice.of(zero).change()).isEqualTo("`count == 0` is always false");
    var negated = with(BuiltIn.OTHER_COMPARISON, "IFEQ", "IFNE", "count", "0");
    assertThat(Advice.of(negated).change()).isEqualTo("`!=` becomes `==`");
    assertThat(Advice.of(negated).fix())
        .isEqualTo("assert the result on both sides of `count != 0`");
  }

  @Test
  void saysTheInputsTheTestsPassed() {
    var seen = Map.of("total", List.of(5L, 20L));
    var removed = with(BuiltIn.REMOVE_CONDITIONAL, "IF_ICMPLT", "never jump", "total", "100");
    assertThat(Advice.of(removed, seen, false).notes())
        .containsExactly("inputs seen: total = 5, 20");
    assertThat(Advice.of(removed).notes()).isEmpty();
    // At a boundary, the inputs say how far from it the tests stay.
    var boundary = with(BuiltIn.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE", "total", "100");
    assertThat(Advice.of(boundary, seen, false).notes())
        .containsExactly(
            "every test calls `discount()` away from the boundary",
            "inputs seen: 5, 20 (none is 100)");
  }

  /// `total >= 100` to `total == 100`: they disagree only where `total > 100`, so that's the test
  /// the card asks for; `<` to `!=`, where it's above.
  @Test
  void namesTheSideWhereAComparisonAndItsMutantDisagree() {
    var equal = Advice.of(with(BuiltIn.COMPARISON, "IF_ICMPLT", "IF_ICMPNE", "total", "100"));
    assertThat(equal.change()).isEqualTo("`>=` becomes `==`");
    assertThat(equal.headline()).isEqualTo("no test checks `discount()` with `total > 100`");
    assertThat(equal.fix())
        .isEqualTo("add a test where `total > 100`, and assert what `discount()` does then");
    var notEqual = Advice.of(with(BuiltIn.COMPARISON, "IFGE", "IFEQ", "count", "0"));
    assertThat(notEqual.change()).isEqualTo("`<` becomes `!=`");
    assertThat(notEqual.headline()).isEqualTo("no test checks `discount()` with `count > 0`");
    // The edge moved, the boundary's words; reversed, the opposite's.
    assertThat(Advice.of(with(BuiltIn.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE", "total", "100")))
        .extracting(Advice::headline)
        .isEqualTo("no test checks `discount()` at `total == 100`");
    assertThat(Advice.of(with(BuiltIn.OTHER_COMPARISON, "IF_ICMPLT", "IF_ICMPGE", "total", "100")))
        .extracting(Advice::headline)
        .isEqualTo("`discount()` behaves the same with `total >= 100` reversed");
    var other = Advice.of(with(BuiltIn.OTHER_COMPARISON, "IF_ICMPLT", "IF_ICMPGT", "total", "100"));
    assertThat(other.headline()).isEqualTo("no test checks `discount()` with `total != 100`");
  }

  @Test
  void aComparisonWithoutItsOperandsNamesTheTwoComparisons() {
    var unknown = Advice.of(mutant(BuiltIn.COMPARISON, "IFEQ", "IFLE", "(I)I"));
    assertThat(unknown.change()).isEqualTo("`!=` becomes `>`");
    assertThat(unknown.headline()).isEqualTo("no test tells `!=` from `>` in `discount()`");
    assertThat(unknown.fix())
        .isEqualTo("add a test where `!=` and `>` give different answers, and assert the result");
  }

  /// Every input on the side the comparison keeps: the card says the tests never go where the
  /// mutant differs.
  @Test
  void saysWhenEveryInputIsOnTheOtherSide() {
    var equal = with(BuiltIn.COMPARISON, "IF_ICMPLT", "IF_ICMPNE", "total", "100");
    assertThat(Advice.of(equal, Map.of("total", List.of(5L, 100L)), false).notes())
        .containsExactly(
            "every test calls `discount()` with `total <= 100`", "inputs seen: 5, 100");
    assertThat(Advice.of(equal, Map.of("total", List.of(5L, 150L)), false).notes())
        .containsExactly("inputs seen: total = 5, 150");
    // `100 < total` to `100 != total`, the name second: they differ below 100.
    var flipped = with(BuiltIn.COMPARISON, "IF_ICMPGE", "IF_ICMPEQ", "100", "total");
    assertThat(Advice.of(flipped, Map.of("total", List.of(150L, 100L)), false).notes())
        .containsExactly(
            "every test calls `discount()` with `100 <= total`", "inputs seen: 150, 100");
  }

  /// `&&` and `||` disagree exactly where one side holds and the other doesn't: the card asks for
  /// that test, naming the sides when the source line gives them.
  @Test
  void aSwappedConnectorAsksForOneSideTrueAndTheOtherFalse() {
    var mutant = mutant(BuiltIn.LOGICAL_CONNECTOR, "IFLE", "&& and || swapped", "(II)I");
    var named =
        Advice.connector(
            mutant,
            Map.of(),
            Optional.of(new Advice.Connective("&&", Optional.of("a > 0"), Optional.of("b < 2"))));
    assertThat(named.change()).isEqualTo("`&&` becomes `||`");
    assertThat(named.headline())
        .isEqualTo(
            "no test checks `discount()` with one of `a > 0` and `b < 2` true and the other false");
    assertThat(named.fix())
        .isEqualTo(
            "add a test where one of `a > 0` and `b < 2` is true and the other false, and assert"
                + " what `discount()` does then");
    var sideless =
        Advice.connector(
            mutant,
            Map.of(),
            Optional.of(new Advice.Connective("||", Optional.empty(), Optional.empty())));
    assertThat(sideless.change()).isEqualTo("`||` becomes `&&`");
    assertThat(sideless.headline())
        .isEqualTo(
            "no test checks `discount()` with one side of the `||` true and the other false");
    // Without the source, or with two connectors in the statement, the card can't say which.
    assertThat(Advice.of(mutant).headline())
        .isEqualTo(
            "no test checks `discount()` with one side of a condition true and the other false");
    assertThat(Advice.of(mutant).before()).isEmpty();
  }

  @Test
  void namesTheArithmeticWhenItsOperandsAreKnown() {
    var known = Advice.of(with(BuiltIn.MATH, "IMUL", "IDIV", "price", "quantity"));
    assertThat(known.headline()).isEqualTo("tests pass even when `*` becomes `/` in `discount()`");
    assertThat(known.fix())
        .isEqualTo(
            "use inputs where `price * quantity` and `price / quantity` differ, and assert the"
                + " result");
    var unknown = Advice.of(mutant(BuiltIn.MATH, "IMUL", "IDIV", "(I)I"));
    assertThat(unknown.fix())
        .isEqualTo("use inputs where `*` and `/` differ, and assert the result");
  }

  @Test
  void otherArithmeticIsTheSameCardAsMath() {
    var known = Advice.of(with(BuiltIn.OTHER_ARITHMETIC, "IADD", "IREM", "price", "quantity"));
    assertThat(known.before()).contains("+");
    assertThat(known.after()).contains("%");
    assertThat(known.headline()).isEqualTo("tests pass even when `+` becomes `%` in `discount()`");
    assertThat(known.fix())
        .isEqualTo(
            "use inputs where `price + quantity` and `price % quantity` differ, and assert the"
                + " result");
  }

  @Test
  void aKeptOperandNamesTheValueNoTestNeeds() {
    var first = Advice.of(with(BuiltIn.KEEP_OPERAND, "IADD", "keep first", "price", "fee"));
    assertThat(first.change()).isEqualTo("`price + fee` becomes `price`");
    assertThat(first.changedTo()).isEqualTo("one value kept");
    assertThat(first.before()).isEmpty();
    assertThat(first.headline()).isEqualTo("no test needs `fee` in `price + fee` in `discount()`");
    assertThat(first.fix())
        .isEqualTo("add a test where `fee` changes the result of `price + fee`, and assert it");

    var second = Advice.of(with(BuiltIn.KEEP_OPERAND, "IADD", "keep second", "price", "fee"));
    assertThat(second.change()).isEqualTo("`price + fee` becomes `fee`");
    assertThat(second.headline())
        .isEqualTo("no test needs `price` in `price + fee` in `discount()`");

    var unknown = Advice.of(mutant(BuiltIn.KEEP_OPERAND, "IMUL", "keep second", "(I)I"));
    assertThat(unknown.change()).isEqualTo("`*` keeps only its second value");
    assertThat(unknown.headline())
        .isEqualTo("no test needs the first value of `*` in `discount()`");
    assertThat(unknown.fix())
        .isEqualTo("add a test where the first value of `*` changes the result, and assert it");

    var unknownFirst = Advice.of(mutant(BuiltIn.KEEP_OPERAND, "IMUL", "keep first", "(I)I"));
    assertThat(unknownFirst.change()).isEqualTo("`*` keeps only its first value");
  }

  @Test
  void aConstantsCardNamesTheValueAndAsksForATestNearIt() {
    var advice = Advice.of(mutant(BuiltIn.CONSTANT_VALUE, "BIPUSH", "constant 100 to 101", "(I)I"));
    assertThat(advice.before()).contains("100");
    assertThat(advice.after()).contains("101");
    assertThat(advice.change()).isEqualTo("the constant `100` becomes `101`");
    assertThat(advice.changedTo()).isEqualTo("changed to 101");
    assertThat(advice.headline()).isEqualTo("no test tells `100` from `101` in `discount()`");
    assertThat(advice.fix())
        .isEqualTo(
            "add a test near where `100` takes effect, whose result would differ with `101`, and"
                + " assert it");
  }

  @Test
  void aFlippedStepSaysWhichWayTheVariableShouldGo() {
    var down = Advice.of(mutant(BuiltIn.FLIP_INCREMENT, "IINC", "count by -1", "(I)I"));
    assertThat(down.change()).isEqualTo("`count` goes down by 1 instead of up");
    assertThat(down.changedTo()).isEqualTo("now goes down");
    assertThat(down.headline()).isEqualTo("no test needs `count` to go up in `discount()`");
    assertThat(down.fix())
        .isEqualTo("add a test whose result depends on `count` going up here, and assert it");

    var up = Advice.of(mutant(BuiltIn.FLIP_INCREMENT, "IINC", "left by 5", "(I)I"));
    assertThat(up.change()).isEqualTo("`left` goes up by 5 instead of down");
    assertThat(up.changedTo()).isEqualTo("now goes up");
    assertThat(up.headline()).isEqualTo("no test needs `left` to go down in `discount()`");
    assertThat(up.fix())
        .isEqualTo("add a test whose result depends on `left` going down here, and assert it");
  }

  @Test
  void aRemovedNegationAsksForTheSign() {
    var advice = Advice.of(mutant(BuiltIn.REMOVE_NEGATION, "INEG", "remove negation", "(I)I"));
    assertThat(advice.change()).isEqualTo("a minus sign is removed, so the value keeps its sign");
    assertThat(advice.changedTo()).isEqualTo("minus sign removed");
    assertThat(advice.headline())
        .isEqualTo("no test checks the sign of the negated value in `discount()`");
    assertThat(advice.fix())
        .isEqualTo("add a test with a value that isn't zero, and assert the sign of the result");
  }

  @Test
  void namesALambdaAndAnAnonymousClassAsTheSourceHasThem() {
    var inLambda =
        new Mutant(
            "com/example/Pricing",
            "lambda$discount$0",
            "(I)Z",
            0,
            5,
            BuiltIn.OTHER_COMPARISON,
            "IF_ICMPLT",
            "IF_ICMPGE",
            Optional.empty(),
            List.of(),
            List.of("total", "100"));
    assertThat(Advice.of(inLambda).headline())
        .isEqualTo("A lambda in `discount()` behaves the same with `total >= 100` reversed");
    var anonymous =
        new Mutant(
            "com/example/Pricing$1",
            "compare",
            "(II)I",
            0,
            5,
            BuiltIn.RETURN_VALUE,
            "IRETURN",
            "return 0",
            Optional.empty());
    assertThat(Advice.of(anonymous).headline())
        .isEqualTo("no test checks what `compare()` in an anonymous class returns here");
  }

  private static Mutant with(
      Operator operator, String original, String replacement, String left, String right) {
    return new Mutant(
        "com/example/Pricing",
        "discount",
        "(I)I",
        0,
        5,
        operator,
        original,
        replacement,
        Optional.empty(),
        List.of(),
        List.of(left, right));
  }

  private static Advice removed(String jump, String replacement) {
    return Advice.of(
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            0,
            5,
            BuiltIn.REMOVE_CONDITIONAL,
            jump,
            replacement,
            Optional.empty(),
            List.of(),
            List.of("inCredit(b)")));
  }

  @Test
  void aChangedReturnSaysTheValueItGivesAndAsksForTheOneItTookAway() {
    var flipped = Advice.of(mutant(BuiltIn.RETURN_VALUE, "IRETURN", "return false", "(I)Z"));
    assertThat(flipped.after()).contains("return false;");
    assertThat(flipped.headline()).isEqualTo("no test checks `discount()` returning `true` here");
    assertThat(flipped.fix())
        .isEqualTo("add a test where `discount()` returns `true` here, and assert it");

    var one = Advice.of(mutant(BuiltIn.RETURN_VALUE, "LRETURN", "return 1L", "(I)J"));
    assertThat(one.change()).isEqualTo("returns `1L` instead");
    assertThat(one.headline()).isEqualTo("no test checks `discount()` returning `0L` here");

    var zero = Advice.of(mutant(BuiltIn.RETURN_VALUE, "IRETURN", "return 0", "(I)I"));
    assertThat(zero.headline()).isEqualTo("no test checks what `discount()` returns here");
    assertThat(zero.fix())
        .isEqualTo(
            "assert the value `discount()` returns on this path, with inputs where it isn't `0`");

    var empty =
        Advice.of(
            mutant(BuiltIn.RETURN_VALUE, "ARETURN", "return List.of()", "()Ljava/util/List;"));
    assertThat(empty.change()).isEqualTo("returns `List.of()` instead");
    assertThat(empty.notes())
        .first()
        .isEqualTo("tests reach this return, but none fails when it returns `List.of()`");
    assertThat(empty.fix())
        .isEqualTo(
            "assert the value `discount()` returns on this path, with inputs where it isn't empty");

    var none =
        Advice.of(mutant(BuiltIn.RETURN_VALUE, "ARETURN", "return null", "()Ljava/lang/Object;"));
    assertThat(none.fix()).isEqualTo("assert the value `discount()` returns on this path");
  }

  @Test
  void namesTheRemovedCall() {
    var advice =
        Advice.of(
            mutant(
                BuiltIn.REMOVE_VOID_CALL,
                "INVOKEVIRTUAL com/example/Audit.record(Ljava/lang/String;)V",
                "remove call",
                "(I)I"));

    assertThat(advice.headline())
        .isEqualTo("no test checks the effect of calling `record()` in `discount()`");
  }

  private static Mutant mutant(
      Operator operator, String original, String replacement, String descriptor) {
    return new Mutant(
        "com/example/Pricing",
        "discount",
        descriptor,
        0,
        5,
        operator,
        original,
        replacement,
        Optional.empty());
  }
}
