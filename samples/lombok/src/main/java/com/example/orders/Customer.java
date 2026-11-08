package com.example.orders;

import lombok.Data;
import lombok.NonNull;

/// `@Data`: getters, setters for what isn't final, a constructor for what is, `equals`, `hashCode`
/// and `toString`. The name is `@NonNull`, so its setter and the constructor check it.
@Data
public class Customer {
  @NonNull private final String name;
  private String email;
  private int loyaltyPoints;

  public String tier() {
    if (loyaltyPoints >= 1000) {
      return "gold";
    }
    return loyaltyPoints >= 100 ? "silver" : "bronze";
  }
}
