package com.example.repeat;

/** Java 11's way, compiled into META-INF/versions/11, with a nested class as well. */
public final class Builtin implements Repeat {
  @Override
  public String times(String word, int count) {
    return count < 1 ? "-" : new Word(word).repeated(count);
  }

  private static final class Word {
    private final String text;

    Word(String text) {
      this.text = text;
    }

    String repeated(int count) {
      return text.repeat(count);
    }
  }
}
