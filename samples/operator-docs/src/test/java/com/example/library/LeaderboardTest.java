package com.example.library;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class LeaderboardTest {
  @Test
  void everyPlayerIsRanked() {
    var players = List.of(new Player("ann", 3), new Player("bo", 1));
    assertEquals(2, Leaderboard.ranking(players).size());
  }
}
