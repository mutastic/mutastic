package com.example.library;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommentsTest {
  @Test
  void aCommentAndAPlainLine() {
    assertTrue(Comments.isComment("#"));
    assertFalse(Comments.isComment("hello"));
  }
}
