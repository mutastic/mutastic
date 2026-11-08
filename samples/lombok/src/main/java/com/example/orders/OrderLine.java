package com.example.orders;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/// `@Getter` and `@Setter` on fields, and `@RequiredArgsConstructor` for the final ones, the
/// `@NonNull` one checked; the gift message's setter checks it too.
@RequiredArgsConstructor
public class OrderLine {
  @Getter @NonNull private final String sku;
  @Getter private final Money price;
  @Getter @Setter private int quantity = 1;
  @Getter @Setter @NonNull private String giftMessage = "";

  public Money subtotal() {
    return price.times(quantity);
  }
}
