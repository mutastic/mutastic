package com.example.stock;

import jakarta.validation.constraints.PositiveOrZero;
import org.checkerframework.checker.index.qual.NonNegative;

/// How many of an item to order: none while any are left, else enough to reach the target.
public final class Restock {
  private Restock() {}

  /// `left` is checked at compile time: the Checker Framework rejects a call that could pass -1.
  public static int toOrder(@NonNegative int left, int target) {
    if (left > 0) {
      return 0;
    }
    return target;
  }

  /// `left` is checked only where something validates it: a direct call can pass -1.
  public static int toOrderValidated(@PositiveOrZero int left, int target) {
    if (left > 0) {
      return 0;
    }
    return target;
  }
}
