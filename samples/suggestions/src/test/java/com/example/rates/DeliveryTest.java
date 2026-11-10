package com.example.rates;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeliveryTest {
  @Test
  void bigOrdersGoFree() {
    assertThat(new Delivery(20).isFree(50)).isTrue(); // nothing checks a total of exactly 20
  }
}
