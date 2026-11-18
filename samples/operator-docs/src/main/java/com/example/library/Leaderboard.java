package com.example.library;

import java.util.Comparator;
import java.util.List;

public final class Leaderboard {
  private Leaderboard() {}

  /// The names, highest score first.
  public static List<String> ranking(List<Player> players) {
    return players.stream()
        .sorted(Comparator.comparingInt(Player::score).reversed())
        .map(Player::name)
        .toList();
  }
}
