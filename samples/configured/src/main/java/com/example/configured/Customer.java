package com.example.configured;

/// A customer, as the shop keeps them.
public final class Customer {
  private final String name;

  public Customer(String name) {
    this.name = name;
  }

  /// How the shop greets them: by the name they typed, without the spaces around it.
  public String greeting() {
    return "Hello, " + name.strip() + "!";
  }

  @Override
  public String toString() {
    return "Customer[" + name + "]";
  }
}
