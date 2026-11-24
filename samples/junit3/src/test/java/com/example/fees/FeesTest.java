package com.example.fees;

import junit.framework.TestCase;

public class FeesTest extends TestCase {
  public void testALongWayLateHasAFee() {
    assertEquals(25, new Fees().lateFee(60));
  }
}
