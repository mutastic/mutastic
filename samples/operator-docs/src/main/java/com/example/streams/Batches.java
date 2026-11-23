package com.example.streams;

import java.util.List;
import java.util.stream.Gatherers;

public final class Batches {
  /// The orders in batches of `size`, each batch sent to the warehouse in one request.
  public static List<List<String>> of(List<String> orders, int size) {
    return orders.stream().gather(Gatherers.windowFixed(size)).toList();
  }
}
