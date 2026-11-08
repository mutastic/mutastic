package com.example.orders;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Singular;
import lombok.ToString;

/// `@Builder` with a `@Singular` list, `@AllArgsConstructor` for it, `@EqualsAndHashCode` and
/// `@ToString` that leave the note out.
@Builder
@AllArgsConstructor
@Getter
@EqualsAndHashCode(exclude = "note")
@ToString(exclude = "note")
public class Order {
  private final Customer customer;
  @Singular private final List<OrderLine> lines;
  private final String note;

  public Money total() {
    long cents = 0;
    for (var line : lines) {
      cents += line.subtotal().getCents();
    }
    return new Money(cents, "EUR");
  }

  public Money discounted() {
    var total = total();
    if (customer.getLoyaltyPoints() >= 1000 && total.getCents() > 10_000) {
      return total.withCents(total.getCents() * 9 / 10);
    }
    return total;
  }
}
