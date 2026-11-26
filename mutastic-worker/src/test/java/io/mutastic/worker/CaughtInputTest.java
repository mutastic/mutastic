package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.proptastic.core.PropertyFailure;
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

  /// proptastic's failure: the assertion's own message, a blank line, then its report, the
  /// counterexample the fact under the header. Found after random tries with shrinking off, it's
  /// unshrunk; shrunk, or from a pinned example or a flaky input, it's as small as it gets.
  @Test
  void proptasticNamesItsCounterexample() {
    var unshrunk =
        proptastic(
            """
            reversesTwice failed after 3 tries (seed 0x0)
              counterexample: xs = [-7, 12]
                      pin it: .example(List.of(-7, 12))""");
    var shrunk =
        proptastic(
            """
            reversesTwice failed after 3 tries (seed 0x0)
              shrunk counterexample: xs = [0, 1]
                             pin it: .example(List.of(0, 1))
                              rerun: PROPTASTIC_SEED=0x0 ./gradlew test --tests 'ListsTest.reversesTwice'""");
    var example =
        proptastic(
            """
            reversesTwice failed on example 2 of 2 (via "mutastic: m1")
              counterexample: xs = [3]""");
    var flaky =
        proptastic(
            """
            reversesTwice failed on try 4, then passed when that input ran again (seed 0x1f)
              counterexample: xs = [9]
                      pin it: .example(List.of(9))
              ! flaky: the same input passed and failed""");

    assertThat(CaughtInput.of(unshrunk, true)).isEqualTo("unshrunk\txs = [-7, 12]");
    assertThat(CaughtInput.of(shrunk, false)).isEqualTo("shrunk\txs = [0, 1]");
    assertThat(CaughtInput.of(example, true)).isEqualTo("shrunk\txs = [3]");
    assertThat(CaughtInput.of(flaky, true)).isEqualTo("shrunk\txs = [9]");
  }

  /// Stacked, when a value spans lines or is wider than the line: each key on its own line, its
  /// value four spaces in below, joined onto one line.
  @Test
  void proptasticsStackedCounterexampleIsJoined() {
    var stacked =
        proptastic(
            """
            sortsAsTheJdkDoes failed after 1 try (seed 0x0)
              counterexample:
                xs = [1475577793, -89898502,
                      23]
                n = 4
              pin it:
                .example(List.of(1475577793, -89898502, 23), 4)""");

    assertThat(CaughtInput.of(stacked, true))
        .isEqualTo("unshrunk\txs = [1475577793, -89898502, 23] n = 4");
  }

  /// The same words in a test's own failure, or anywhere but under proptastic's header, aren't its
  /// counterexample.
  @Test
  void aCounterexampleLineOutsideProptasticsReportIsNotRead() {
    var mimic =
        new AssertionError(
            """
            reversesTwice failed after 3 tries (seed 0x0)
              counterexample: from the user""");
    var noHeader = proptastic("  counterexample: n = 3");

    assertThat(CaughtInput.of(mimic, true)).isEqualTo(CaughtInput.NONE);
    assertThat(CaughtInput.of(noHeader, true)).isEqualTo(CaughtInput.NONE);
  }

  /// proptastic's failure for `report`, after the assertion's own message, as it throws it.
  private static PropertyFailure proptastic(String report) {
    var original = new AssertionError("expected: <[1, 0]> but was: <[0, 1]>");
    return new PropertyFailure(original.getMessage() + "\n\n" + report, original);
  }
}
