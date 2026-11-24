package com.example.fees;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

public class FeesTest {
  @Test
  public void aLongWayLateHasAFee() {
    assertEquals(new Fees().lateFee(60), 25);
  }
}
