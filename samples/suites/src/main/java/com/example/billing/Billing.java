package com.example.billing;

public class Billing {
  /// Late from the 30th day on.
  public boolean late(int days) {
    return days >= 30;
  }
}
