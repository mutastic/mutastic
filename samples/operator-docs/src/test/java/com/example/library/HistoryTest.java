package com.example.library;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HistoryTest {
  @Test
  void aVisitedPageIsCurrent() {
    var history = new History();
    history.visit("home");
    assertEquals("home", history.current());
  }
}
