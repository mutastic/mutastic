package com.example.arithmetic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PermissionsTest {
  @Test
  void writeIsAddedToRead() {
    assertEquals(3, Permissions.grant(1, 2));
  }
}
