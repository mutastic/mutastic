package com.example.optional;

import java.util.Optional;

public final class Badges {
  /// What a profile shows: the nickname if there is one, and whether it's a nickname.
  public record Badge(String shown, boolean nickname) {}

  public static Badge badge(String name, Optional<String> nickname) {
    return new Badge(nickname.orElse(name), nickname.isPresent());
  }
}
