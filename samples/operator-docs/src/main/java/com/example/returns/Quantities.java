package com.example.returns;

public final class Quantities {
  public static int parse(String text) {
    if (text.isEmpty()) throw new IllegalArgumentException("no quantity given");
    return Integer.parseInt(text);
  }
}
