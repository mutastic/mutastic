package com.example.configured.generated;

/// Written by a code generator, and tested where it's generated: mutastic.yaml's rule skips it.
public final class Checksums {
  private Checksums() {}

  public static int of(byte[] bytes) {
    int sum = 1;
    for (byte b : bytes) {
      sum += b;
    }
    return sum;
  }
}
