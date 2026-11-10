package com.example.rates;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DiscountsTest {
  @Test
  void bigTotalsGetADiscount() {
    assertThat(Discounts.discount(250)).isEqualTo(10); // nothing checks the edge
  }

  @Test
  void bulkOrdersGetADiscount() {
    assertThat(Discounts.bulk(80)).isEqualTo(5);
  }

  @Test
  void aBigChoiceHasTwoColumns() {
    assertThat(Discounts.columns(10)).isEqualTo(2);
  }
}
