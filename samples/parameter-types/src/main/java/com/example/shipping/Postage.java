package com.example.shipping;

/// What a parcel costs to send.
public final class Postage {
  private Postage() {}

  /// A parcel over 500 grams costs 7, a lighter one 3.
  public static int cost(int grams) {
    return grams > 500 ? 7 : 3;
  }
}
