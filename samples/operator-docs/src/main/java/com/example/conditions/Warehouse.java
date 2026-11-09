package com.example.conditions;

import java.util.ArrayList;
import java.util.List;

/// The orders that go out: the paid ones.
public final class Warehouse {
  public record Order(String id, boolean paid) {}

  public static List<Order> toShip(List<Order> orders) {
    var ship = new ArrayList<Order>();
    for (Order order : orders) {
      if (order.paid()) {
        ship.add(order);
      }
    }
    return ship;
  }
}
