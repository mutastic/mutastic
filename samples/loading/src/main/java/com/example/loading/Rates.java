package com.example.loading;

import java.util.HashMap;
import java.util.Map;

/// Discount rates, read from a table once, when the class loads: `parse` runs only then.
public final class Rates {
  private static final Map<String, Integer> RATES = parse("gold=20,silver=10");

  private Rates() {}

  public static int of(String tier) {
    return RATES.getOrDefault(tier, 0);
  }

  private static Map<String, Integer> parse(String table) {
    var rates = new HashMap<String, Integer>();
    for (String entry : table.split(",")) {
      int equals = entry.indexOf('=');
      rates.put(entry.substring(0, equals), Integer.parseInt(entry.substring(equals + 1)));
    }
    return rates;
  }
}
