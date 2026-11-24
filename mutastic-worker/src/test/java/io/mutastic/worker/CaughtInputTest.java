package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/// Each framework's failure, as its source writes it, and the input read from it.
class CaughtInputTest {
  @Test
  void junitQuickcheckNamesItsArguments() {
    var unshrunk =
        new AssertionError(
            """
            Property named 'doubles' failed (expected:<2> but was:<0>)
            With arguments: [1]
            Seeds for reproduction: [-4211]""");
    var shrunk =
        new AssertionError(
            """
            Property named 'doubles' failed (expected:<2> but was:<0>)
            With arguments: [1]
            Original failure message: expected:<568> but was:<0>
            First arguments found to also provoke a failure: [284]
            Seeds for reproduction: [-4211]""");
    var error =
        new AssertionError(
            "Unexpected error in property doubles with args [3, \"a b\"] and seeds [-55, 7]",
            new ArithmeticException("/ by zero"));

    // Its shrinking can't be turned off from outside: what it names is as it shrank it.
    assertThat(CaughtInput.of(unshrunk, true)).isEqualTo("shrunk\t[1]");
    assertThat(CaughtInput.of(shrunk, true)).isEqualTo("shrunk\t[1]");
    assertThat(CaughtInput.of(error, true)).isEqualTo("shrunk\t[3, \"a b\"]");
  }

  @Test
  void quickTheoriesNamesItsSmallestValue() {
    var falsified =
        new AssertionError(
            """
            Property falsified after 1 example(s)\s
            Smallest found falsifying value(s) :-
            [1000, 764,
             -624]
            Other found falsifying value(s) :-\s

            \s
            Seed was 1""");
    var threw =
        new AssertionError(
            """
            Property falsified after 3 example(s)\s
            Smallest found falsifying value(s) :-
            {18, 2}
            Cause was :-
            java.lang.IllegalStateException: no
            \tat com.example.props.Ages.isAdult(Ages.java:9)
            Other found falsifying value(s) :-\s
            {18, 7}
            \s
            Seed was 1""");

    assertThat(CaughtInput.of(falsified, true)).isEqualTo("unshrunk\t[1000, 764, -624]");
    assertThat(CaughtInput.of(threw, false)).isEqualTo("shrunk\t{18, 2}");
  }

  @Test
  void kotestNamesEachArgumentWithoutWhatItShrankFrom() {
    var failed =
        new AssertionError(
            """
            Property failed after 3 attempts

            \tArg 0: -1 (shrunk from -13)
            \tArg 1: "a (b)"

            Repeat this test by using seed 0

            Caused by: expected:<true> but was:<false>""");

    assertThat(CaughtInput.of(failed, true)).isEqualTo("unshrunk\tArg 0: -1, Arg 1: \"a (b)\"");
  }

  /// A cause's input is found too: a framework's error wrapped by the test's own.
  @Test
  void aCauseNamesTheInputToo() {
    var wrapped =
        new IllegalStateException(
            "checking ages",
            new AssertionError("Property failed after 1 attempts\n\n\tArg 0: 18\n"));

    assertThat(CaughtInput.of(wrapped, true)).isEqualTo("unshrunk\tArg 0: 18");
  }

  @Test
  void aFailureThatNamesNoInputHasNone() {
    assertThat(CaughtInput.of(new AssertionError("expected: <5> but was: <4>"), true))
        .isEqualTo(CaughtInput.NONE);
    assertThat(CaughtInput.of(new NullPointerException(), true)).isEqualTo(CaughtInput.NONE);
    assertThat(CaughtInput.of(new AssertionError("Property failed after 2 attempts\n"), true))
        .isEqualTo(CaughtInput.NONE);
  }

  /// One line, no tab, the test class's copy named as the build names it, and at most 300
  /// characters, then `…`.
  @Test
  void anInputIsOneShortLine() {
    var renamed =
        new AssertionError("Property failed after 1 attempts\n\tArg 0: a.BTest__m7@1f\tx");
    var longList = new StringBuilder("[");
    for (int i = 0; i < 200; i++) {
      longList.append(i).append(", ");
    }
    var tooLong = new AssertionError("With arguments: " + longList + "]");

    assertThat(CaughtInput.of(renamed, true)).isEqualTo("unshrunk\tArg 0: a.BTest@1f x");
    var cut = CaughtInput.of(tooLong, true).substring("shrunk\t".length());
    assertThat(cut.codePointCount(0, cut.length())).isEqualTo(CaughtInput.LONGEST + 1);
    assertThat(cut).startsWith("[0, 1, 2").endsWith("…");
  }
}
