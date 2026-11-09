package com.example.library;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FilesTest {
  @Test
  void aTextFile() {
    assertEquals("txt", Files.extension("notes.txt"));
  }
}
