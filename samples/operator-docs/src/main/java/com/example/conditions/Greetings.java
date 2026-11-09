package com.example.conditions;

public final class Greetings {
  public static String greeting(boolean member) {
    if (member) {
      return "welcome back";
    }
    return "welcome";
  }
}
