package com.example.library;

public final class Files {
  public static String extension(String file) {
    return file.substring(file.indexOf('.') + 1);
  }
}
