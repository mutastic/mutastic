package com.example.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class GreetingTest {
  @Test
  void inThePersonsLanguage() {
    assertEquals("Bonjour, Ana!", Greeting.hello("Ana", Locale.FRENCH));
  }

  @Test
  void withNoName() {
    assertEquals("Hello!", Greeting.hello(" ", Locale.ROOT));
  }
}
