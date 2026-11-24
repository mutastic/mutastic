package com.example.fees;

public class Fees {
  public int lateFee(int days) {
    return days > 30 ? 25 : 0;
  }
}
