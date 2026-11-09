package com.example.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ZonesTest {
  private final Zones zones = new Zones(1, 3, 7);

  @Test
  void nearAndAbroad() {
    assertEquals(1, zones.days(1));
    assertEquals(7, zones.days(9));
  }

  @Test
  void everyZoneWithinAWeek() {
    for (int zone = 1; zone <= 3; zone++) {
      assertTrue(zones.days(zone) <= 7);
    }
  }
}
