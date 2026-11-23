package com.example.streams;

import java.util.List;
import java.util.stream.Gatherers;

public final class Statement {
  /// The balance after each payment: a line of the statement for each.
  public static List<Integer> balances(List<Integer> payments) {
    return payments.stream().gather(Gatherers.scan(() -> 0, Integer::sum)).toList();
  }
}
