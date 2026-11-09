package com.example.conditions;

public final class Shipping {
  public static boolean canShip(boolean paid, boolean inStock) {
    return paid && inStock;
  }
}
