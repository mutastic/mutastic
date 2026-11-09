package com.example.arithmetic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BillTest {
  @Test
  void aBillIsShared() {
    assertEquals(2, Bill.perPerson(10, 4));
  }
}
