package com.example.loading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RatesTest {
  @Test
  void goldAndSilverHaveTheirRates() {
    assertEquals(20, Rates.of("gold"));
    assertEquals(10, Rates.of("silver"));
  }

  @Test
  void anyOtherTierHasNone() {
    assertEquals(0, Rates.of("bronze"));
  }
}
