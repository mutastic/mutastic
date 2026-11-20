package com.example.arid;

/** An HTTP header's value: everything after its name and the first colon. */
public final class Headers {
  private Headers() {}

  public static String value(String line) {
    return line.split(":", 2)[1].strip();
  }
}
