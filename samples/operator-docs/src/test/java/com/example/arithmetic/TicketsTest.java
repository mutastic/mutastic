package com.example.arithmetic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TicketsTest {
  @Test
  void aFamilyOfFour() {
    assertEquals(4, Tickets.seats(2, 2));
  }
}
