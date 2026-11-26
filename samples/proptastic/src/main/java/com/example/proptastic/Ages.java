package com.example.proptastic;

/// Ages, checked only by a property.
public final class Ages {
  private Ages() {}

  /// Whether someone of `age` is an adult: 18 or over.
  public static boolean isAdult(int age) {
    return age >= 18;
  }
}
