package com.example.arguments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VolumeTest {
  @Test
  void tooLoudIsTurnedDown() {
    assertEquals(10, new Volume(0, 10).set(15));
  }
}
