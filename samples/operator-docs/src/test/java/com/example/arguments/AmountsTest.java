package com.example.arguments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AmountsTest {
  @Test
  void fixedShowsTwoDecimalPlaces() {
    assertEquals("2.50", Amounts.shown(2.5, true));
  }

  @Test
  void otherwiseAsFewAsItNeeds() {
    assertEquals("2.5", Amounts.shown(2.5, false));
  }
}
