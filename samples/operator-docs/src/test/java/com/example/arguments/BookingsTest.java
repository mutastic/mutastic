package com.example.arguments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BookingsTest {
  @Test
  void aBookingIsInTheGuestsName() {
    assertEquals("Ann", Bookings.book("Ann", "12").guest());
  }
}
