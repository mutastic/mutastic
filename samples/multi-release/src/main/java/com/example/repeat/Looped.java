package com.example.repeat;

/** Java 8's way. */
public final class Looped implements Repeat {
  @Override
  public String times(String word, int count) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < count; i++) {
      out.append(word);
    }
    return out.length() == 0 ? "-" : out.toString();
  }
}
