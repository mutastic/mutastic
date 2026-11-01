package io.mutastic.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolTest {
  /// The engine reads `-` as no values; an empty field isn't a number.
  @Test
  void inputsRoundTripIncludingNone() {
    assertThat(Worker.encoded(List.of(1L, -2L))).isEqualTo("1;-2");
    assertThat(Worker.encoded(List.of())).isEqualTo("-");
    assertThat(Worker.candidates(Worker.encoded(List.of()) + "|" + Worker.encoded(List.of(3L))))
        .containsExactly(List.of(), List.of(3L));
  }
}
