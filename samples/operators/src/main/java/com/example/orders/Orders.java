package com.example.orders;

import java.util.List;

public class Orders {
  /// How many orders are large: 100 or more.
  public long large(List<Integer> totals) {
    return totals.stream().filter(total -> total >= 100).count();
  }

  /// The price with shipping added.
  public int total(int price, int shipping) {
    return price + shipping;
  }

  /// A discount code as customers type it, tidied: no spaces around it, upper case.
  public String code(String typed) {
    return typed.strip().toUpperCase();
  }
}
