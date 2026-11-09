package com.example.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiptsTest {
  @Test
  void oneItem() {
    assertEquals("tea", Receipts.text(List.of("tea")).strip());
  }
}
