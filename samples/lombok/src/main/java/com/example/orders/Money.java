package com.example.orders;

import lombok.Value;
import lombok.With;

/// An amount in one currency: `@Value` writes the constructor, getters, `equals`, `hashCode` and
/// `toString`, and `@With` a copy with one field changed.
@Value
@With
public class Money {
  long cents;
  String currency;

  public Money plus(Money other) {
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException(currency + " and " + other.currency);
    }
    return new Money(cents + other.cents, currency);
  }

  public Money times(int quantity) {
    return withCents(cents * quantity);
  }

  public boolean isZero() {
    return cents == 0;
  }
}
