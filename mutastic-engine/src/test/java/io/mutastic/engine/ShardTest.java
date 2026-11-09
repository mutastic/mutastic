package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/// Shares of a run's mutants: each mutant in exactly one, and none of them left nearly empty.
class ShardTest {
  @Test
  void eachMutantIsInExactlyOneShare() {
    var ids =
        IntStream.range(0, 400).mapToObj(seed -> "m%08x".formatted(seed * 2654435761L)).toList();

    for (var id : ids) {
      assertThat(
              IntStream.rangeClosed(1, 4).filter(number -> new Shard(number, 4).holds(id)).count())
          .as(id)
          .isOne();
      assertThat(Shard.WHOLE.holds(id)).isTrue();
    }
    for (int n = 1; n <= 4; n++) {
      var share = new Shard(n, 4);
      assertThat(ids.stream().filter(share::holds).count()).as("share %d", n).isBetween(70L, 130L);
    }
  }

  @Test
  void aShareIsOneOfHowMany() {
    assertThat(Shard.WHOLE.whole()).isTrue();
    assertThat(new Shard(1, 2).whole()).isFalse();
    assertThatThrownBy(() -> new Shard(3, 2)).hasMessage("no share 3 of 2");
    assertThatThrownBy(() -> new Shard(1, Shard.MOST + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
