package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AmountsTest {
  @Test
  void saysWhatIsWrongWithANegativeAmount() {
    var refused = assertThrows(IllegalArgumentException.class, () -> Amounts.parse("-1"));
    assertEquals("amount must not be negative", refused.getMessage());
  }
}
