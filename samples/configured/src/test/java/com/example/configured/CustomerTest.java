package com.example.configured;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CustomerTest {
  @Test
  void greetsByTheNameWithoutSpaces() {
    assertEquals("Hello, Ada!", new Customer(" Ada ").greeting());
  }
}
