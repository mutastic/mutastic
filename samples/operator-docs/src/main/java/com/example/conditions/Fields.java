package com.example.conditions;

import java.util.List;
import java.util.function.Predicate;

public final class Fields {
  /// How many of a form's fields are filled in.
  public static long filled(List<String> values) {
    return values.stream().filter(Predicate.not(String::isEmpty)).count();
  }
}
