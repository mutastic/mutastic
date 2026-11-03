package com.example.rates;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

public class RatesTest {
  // Far from the edge: `>=` becoming `>` goes unnoticed.
  @Test
  public void aLongWayLateHasAFee() {
    assertEquals(new Rates().lateFee(60), 25);
    assertEquals(new Rates().lateFee(1), 0);
  }

  // Would catch it, but the build leaves the `slow` group out, so mutastic must too.
  @Test(groups = "slow")
  public void theThirtiethDayHasAFee() {
    assertEquals(new Rates().lateFee(30), 25);
  }
}
