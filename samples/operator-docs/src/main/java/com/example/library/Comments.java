package com.example.library;

public final class Comments {
  public static boolean isComment(String line) {
    return line.startsWith("#");
  }
}
