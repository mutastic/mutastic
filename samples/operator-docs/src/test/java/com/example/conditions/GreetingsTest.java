package com.example.conditions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GreetingsTest {
  @Test
  void membersAreWelcomedBack() {
    assertEquals("welcome back", Greetings.greeting(true));
  }
}
