package com.example.stock;

public class Stock {
  /// Reorder when stock falls to the reorder level.
  public boolean needsReorder(int onHand, int reorderLevel) {
    return onHand <= reorderLevel;
  }

  public int afterSale(int onHand, int sold) {
    return onHand - sold;
  }
}
