package com.example.calc;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/// JUnit 4, on the vintage engine: its test methods must be public. Taking nothing away is all its
/// one row checks.
@RunWith(Parameterized.class)
public class SubtractJUnit4Test {
  @Parameters(name = "{index}: {0}-{1}={2}")
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {{10, 0, 10}});
  }

  private final int a;
  private final int b;
  private final int expected;

  public SubtractJUnit4Test(int a, int b, int expected) {
    this.a = a;
    this.b = b;
    this.expected = expected;
  }

  @Test
  public void subtracts() {
    assertEquals(expected, new Calculator().subtract(a, b));
  }
}
