package com.example.conditions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ModerationTest {
  @Test
  void aLongShoutIsHeld() {
    Predicate<String> shouted = text -> text.equals(text.toUpperCase());
    Predicate<String> isLong = text -> text.length() > 10;
    var held = Moderation.held(shouted, isLong);
    assertTrue(held.test("PLEASE READ THIS"));
    assertFalse(held.test("hi"));
  }
}
