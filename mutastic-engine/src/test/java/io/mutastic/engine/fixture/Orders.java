package io.mutastic.engine.fixture;

import java.util.List;

/// Stream code, for the stream rules.
public class Orders {
  public long large(List<Integer> totals) {
    return totals.stream().filter(total -> total >= 100).count();
  }

  public List<Integer> firstTwo(List<Integer> totals) {
    return totals.stream().sorted().limit(2).toList();
  }

  public long distinctCount(List<Integer> totals) {
    return totals.stream().sorted().count();
  }

  public boolean anyLarge(List<Integer> totals) {
    return totals.stream().anyMatch(total -> total >= 100);
  }
}
