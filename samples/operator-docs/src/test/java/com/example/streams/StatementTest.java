package com.example.streams;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatementTest {
  @Test
  void aPayment() {
    assertEquals(List.of(10), Statement.balances(List.of(10)));
  }
}
