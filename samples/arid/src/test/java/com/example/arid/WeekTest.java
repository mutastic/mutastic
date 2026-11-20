package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WeekTest {
  @Test
  void hasADayForEachDayOfTheWeek() {
    assertEquals(7, Week.perDay(new int[] {0, 6}).length);
  }
}
