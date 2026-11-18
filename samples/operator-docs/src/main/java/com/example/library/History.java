package com.example.library;

import java.util.ArrayDeque;
import java.util.Deque;

/// The pages a browser tab has visited, oldest first.
public final class History {
  private final Deque<String> pages = new ArrayDeque<>();

  public void visit(String page) {
    pages.addLast(page);
  }

  /// The page the tab is on: the last one visited.
  public String current() {
    return pages.peekLast();
  }
}
