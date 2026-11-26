package com.example.fees;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FeesTest {
  @Test
  void aLongWayLateHasAFee() {
    assertEquals(25, new Fees().lateFee(60));
  }
}
