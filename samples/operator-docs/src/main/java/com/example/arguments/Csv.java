package com.example.arguments;

public final class Csv {
  /// Where the next field ends: the first comma at or after `from`.
  public static int nextComma(String line, int from) {
    return line.indexOf(',', from);
  }
}
