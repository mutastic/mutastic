package com.example.streams;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TotalsTest {
  @Test
  void totalsAddUp() {
    assertEquals(3, Totals.logged(List.of(1, 2), new ArrayList<>()));
  }
}
