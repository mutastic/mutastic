package com.example.optional;

import java.util.Optional;

public final class Pages {
  private final int standard;

  public Pages(int standard) {
    this.standard = standard;
  }

  /// The chosen page size, or the standard one when none is chosen.
  public int size(Integer chosen) {
    Optional<Integer> custom = Optional.ofNullable(chosen);
    return custom.orElse(standard);
  }
}
