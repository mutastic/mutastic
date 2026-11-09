package com.example.calls;

import java.util.List;

public final class Receipts {
  /// A receipt: each item on a line of its own.
  public static String text(List<String> items) {
    var out = new StringBuilder();
    for (String item : items) {
      out.append(item);
      out.append(System.lineSeparator());
    }
    return out.toString();
  }
}
