package com.example.library;

import java.util.List;

public final class Headers {
  public static String header(List<String> columns) {
    return String.join(", ", columns);
  }
}
