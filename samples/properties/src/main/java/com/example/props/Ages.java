package com.example.props;

/** Who counts as an adult. */
public final class Ages {
  private Ages() {}

  /** Whether someone of {@code age} is an adult: 18 or older. */
  public static boolean isAdult(int age) {
    return age >= 18;
  }
}
