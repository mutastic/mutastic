package com.example.repeat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** These pass only when the Java 11 class in META-INF/versions/11 loads. */
class RepeatTest {
  @Test
  void usesTheJava11Class() {
    assertEquals("Builtin", Repeat.best().getClass().getSimpleName());
  }

  @Test
  void repeatsAWord() {
    assertEquals("abab", Repeat.best().times("ab", 2));
  }

  @Test
  void repeatsOnce() {
    assertEquals("ab", Repeat.best().times("ab", 1));
  }

  @Test
  void givesADashForNone() {
    assertEquals("-", Repeat.best().times("ab", 0));
  }

  @Test
  void loopsOnJava8() {
    assertEquals("ababab", new Looped().times("ab", 3));
    assertEquals("-", new Looped().times("ab", 0));
  }
}
