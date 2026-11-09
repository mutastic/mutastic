package com.example.fast;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.shapes.Area;
import org.junit.jupiter.api.Test;

class FastAreaTest {
  // Area.fastest() is in shapes, and loads this module's FastArea by name.
  @Test
  void theFastestIsThisModules() {
    assertEquals(FastArea.class, Area.fastest().getClass());
  }

  @Test
  void squares() {
    assertEquals(9, Area.fastest().of(3));
    assertEquals(0, Area.fastest().of(-3));
  }
}
