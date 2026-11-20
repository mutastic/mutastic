package com.example.arid;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Orders, which are never null. */
public final class Orders {
  private final List<String> orders = new ArrayList<>();

  public void add(String order) {
    Objects.requireNonNull(order);
    orders.add(order);
  }

  public int size() {
    return orders.size();
  }
}
