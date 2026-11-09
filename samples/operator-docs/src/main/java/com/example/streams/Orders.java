package com.example.streams;

import java.util.List;

public final class Orders {
  public record Order(int total, boolean paid) {}

  /// The total of the orders that are paid.
  public static int paidTotal(List<Order> orders) {
    return orders.stream().filter(Order::paid).mapToInt(Order::total).sum();
  }
}
