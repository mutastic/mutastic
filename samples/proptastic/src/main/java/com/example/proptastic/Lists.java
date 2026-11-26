package com.example.proptastic;

import java.util.ArrayList;
import java.util.List;

/// Lists, checked only by properties.
public final class Lists {
  private Lists() {}

  /// `xs` back to front.
  public static List<Integer> reversed(List<Integer> xs) {
    var out = new ArrayList<>(xs);
    for (int i = 0, j = out.size() - 1; i < j; i++, j--) {
      var swap = out.get(i);
      out.set(i, out.get(j));
      out.set(j, swap);
    }
    return out;
  }

  /// `xs` smallest first, by insertion.
  public static List<Integer> sorted(List<Integer> xs) {
    var out = new ArrayList<Integer>();
    for (var x : xs) {
      int at = out.size();
      while (at > 0 && out.get(at - 1) > x) {
        at--;
      }
      out.add(at, x);
    }
    return out;
  }
}
