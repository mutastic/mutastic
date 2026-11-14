package com.example.checkout;

import java.util.List;

public class Checkout {
  /// 10% off orders of 100 or more.
  public int discount(int total) {
    if (total >= 100) {
      return total / 10;
    }
    return 0;
  }

  /// What `quantity` items at `price` each cost, less any discount, which goes on the receipt.
  public int pay(int quantity, int price, List<String> receipt) {
    int total = quantity * price;
    int off = discount(total);
    receipt.add("discount " + off);
    return total - off;
  }
}
