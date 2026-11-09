package com.example.optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class NicknamesTest {
  @Test
  void anAllowedNickname() {
    var nicknames = new Nicknames(nickname -> !nickname.equals("admin"));
    assertEquals(Optional.of("annie"), nicknames.claim(Optional.of("annie")));
  }
}
