package com.example.arguments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UsersTest {
  @Test
  void usernamesAreLowerCase() {
    assertEquals("ann", Users.username("Ann"));
  }
}
