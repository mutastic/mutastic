package com.example.repeat;

/** Repeats a word, or gives a dash for none. */
public interface Repeat {
  String times(String word, int count);

  /** {@link Builtin}, on Java 11 and later; {@link Looped} before. */
  static Repeat best() {
    try {
      return (Repeat)
          Class.forName("com.example.repeat.Builtin").getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      return new Looped();
    }
  }
}
