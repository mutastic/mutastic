package com.example.orders;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/// One constraint a test needs, and one none does.
public class Order {
  @NotNull private final String name;

  @Size(max = 3)
  private final String code;

  @NotNull private final int quantity;

  public Order(String name, String code, int quantity) {
    this.name = name;
    this.code = code;
    this.quantity = quantity;
  }

  public String name() {
    return name + code + quantity;
  }
}
