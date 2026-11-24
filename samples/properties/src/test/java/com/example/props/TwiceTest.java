package com.example.props;

import static org.junit.Assert.assertEquals;

import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;
import org.junit.runner.RunWith;

/** junit-quickcheck, on JUnit 4: twice any number is that number added to itself. */
@RunWith(JUnitQuickcheck.class)
public class TwiceTest {
  @Property
  public void doubles(int x) {
    assertEquals((long) x + x, Numbers.twice(x));
  }
}
