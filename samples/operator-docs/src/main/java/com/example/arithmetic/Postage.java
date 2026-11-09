package com.example.arithmetic;

/// A parcel over 500 grams goes as heavy.
public final class Postage {
  public static boolean isHeavy(int grams) {
    return grams > 500;
  }
}
