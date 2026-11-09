package com.example.optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class BadgesTest {
  @Test
  void theNicknameIsShown() {
    assertEquals("Annie", Badges.badge("Ann", Optional.of("Annie")).shown());
    assertEquals("Ann", Badges.badge("Ann", Optional.empty()).shown());
  }
}
