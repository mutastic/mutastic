package com.example.library;

import java.util.regex.Pattern;

/// An order number is digits, and nothing else.
public final class OrderNumbers {
  private static final Pattern DIGITS = Pattern.compile("^\\d+$");

  public static boolean isValid(String text) {
    return DIGITS.matcher(text).find();
  }
}
