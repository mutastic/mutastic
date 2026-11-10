package com.example.app;

import com.example.labels.Labels;
import java.util.Locale;

/// A greeting in the person's language, from this module's messages.
public final class Greeting {
  static final String MESSAGES = "com.example.app.Messages";

  private Greeting() {}

  public static String hello(String name, Locale locale) {
    var greeting = Labels.text(MESSAGES, "hello", locale);
    return name.isBlank() ? greeting + "!" : greeting + ", " + name + "!";
  }
}
