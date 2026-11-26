package com.example.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CacheTest {
  @Test
  void aBigCacheIsCapped() {
    assertEquals(10, $Cache.size(50));
  }
}
