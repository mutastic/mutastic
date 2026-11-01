package io.mutastic.engine.fixture;

public class Pricing {
  public int discount(int total) {
    if (total >= 100) {
      return total / 10;
    }
    return 0;
  }

  public enum Tier { GOLD, SILVER }
}
