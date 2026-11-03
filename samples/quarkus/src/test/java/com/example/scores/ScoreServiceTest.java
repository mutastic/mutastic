package com.example.scores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ScoreServiceTest {
  @Inject ScoreService scores;

  @Test
  void topLeavesOutScoresBelowTheMinimum() {
    // No repeats and fewer than n: only the filter is really tested.
    var top = scores.top(Multi.createFrom().items(90, 50, 70), 60, 5).await().indefinitely();
    assertEquals(List.of(90, 70), top);
  }

  @Test
  void aLoadedScoreComesThrough() {
    // Never fails, so nothing tests the recovery.
    assertEquals(80, scores.scoreOrZero(Uni.createFrom().item(80)).await().indefinitely());
  }

  @Test
  void ninetyFiveIsHighAndFiftyIsNot() {
    assertTrue(scores.isHigh(95));
    assertFalse(scores.isHigh(50));
  }
}
