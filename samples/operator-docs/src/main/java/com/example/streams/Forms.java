package com.example.streams;

import java.util.List;

public final class Forms {
  /// Whether any answer on a form is left blank.
  public static boolean anyBlank(List<String> answers) {
    return answers.stream().anyMatch(String::isBlank);
  }
}
