package com.example.optional;

import java.util.Optional;
import java.util.function.Predicate;

public final class Nicknames {
  private final Predicate<String> allowed;

  public Nicknames(Predicate<String> allowed) {
    this.allowed = allowed;
  }

  /// The nickname asked for, if the rules allow it.
  public Optional<String> claim(Optional<String> asked) {
    return asked.filter(allowed);
  }
}
