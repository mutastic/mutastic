package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PairsTest {
  @Test
  void givesEachValueTwice() {
    assertEquals(List.of(1, 1), Pairs.doubled(List.of(1)));
    assertEquals(List.of(), Pairs.doubled(List.of()));
  }
}
