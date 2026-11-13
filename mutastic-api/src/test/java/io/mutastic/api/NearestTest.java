package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NearestTest {
  @Test
  void aTranspositionIsOneEdit() {
    assertThat(Nearest.distance("tset", "test")).isEqualTo(1);
    assertThat(Nearest.distance("tset", "set")).isEqualTo(1);
    assertThat(Nearest.distance("cor", "core")).isEqualTo(1);
    assertThat(Nearest.distance("nope", "core")).isEqualTo(2);
    assertThat(Nearest.distance("", "app")).isEqualTo(3);
    assertThat(Nearest.distance("APP", "app")).isZero();
  }

  /// The table both editions' tests read, mutastic-rs's `crates/mutastic/tests/typos.tsv` as it is,
  /// so a typo gets the same answer from either.
  @Test
  void everyTypoInTheSharedTableGetsItsAnswer() throws IOException {
    for (String line : table()) {
      var columns = line.split("\t", -1);
      var expected = columns[2].equals("-") ? Optional.<String>empty() : Optional.of(columns[2]);

      assertThat(Nearest.name(columns[0], List.of(columns[1].split(",", -1))))
          .as(line)
          .isEqualTo(expected);
    }
  }

  @Test
  void punctuationCountsForNoEdits() {
    assertThat(Nearest.name("--formt", List.of("--format", "--fail-on-new"), Nearest::bare))
        .hasValue("--format");
    assertThat(Nearest.name("-threshold", List.of("--threshold"), Nearest::bare))
        .hasValue("--threshold");
  }

  @Test
  void anIdIsOfferedOnlyOneSlipAway() {
    var ids = List.of("m855c8e3c", "m2e491c40");

    assertThat(Nearest.id("m855c83ec", ids)).hasValue("m855c8e3c");
    assertThat(Nearest.id("m855c8e3d", ids)).hasValue("m855c8e3c");
    assertThat(Nearest.id("m855c8eff", ids)).isEmpty();
  }

  private static List<String> table() throws IOException {
    try (InputStream in =
        Objects.requireNonNull(NearestTest.class.getResourceAsStream("typos.tsv"))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(line -> !line.isBlank() && !line.startsWith("#"))
          .toList();
    }
  }
}
