package com.example.conditions;

import java.util.function.Predicate;

/// The messages held for a moderator: the shouted ones that are long too.
public final class Moderation {
  public static Predicate<String> held(Predicate<String> shouted, Predicate<String> isLong) {
    return shouted.and(isLong);
  }
}
