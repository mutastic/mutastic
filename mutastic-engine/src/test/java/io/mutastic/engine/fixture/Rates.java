package io.mutastic.engine.fixture;

import java.util.HashMap;
import java.util.Map;

/// Rates read from a table once, when the class loads: `parse` runs only then.
public final class Rates {
  private static final Map<String, Integer> RATES = parse("gold=20");

  private Rates() {}

  public static int of(String tier) {
    return RATES.getOrDefault(tier, 0);
  }

  private static Map<String, Integer> parse(String table) {
    var rates = new HashMap<String, Integer>();
    int equals = table.indexOf('=');
    rates.put(table.substring(0, equals), Integer.parseInt(table.substring(equals + 1)));
    return rates;
  }
}
