package com.example.props;

import java.util.ArrayList;
import java.util.List;

/** Lists of whole numbers. */
public final class Lists {
  private Lists() {}

  /** The list in reverse order. */
  public static List<Integer> reversed(List<Integer> xs) {
    var out = new ArrayList<Integer>(xs.size());
    for (int at = xs.size() - 1; at >= 0; at--) {
      out.add(xs.get(at));
    }
    return out;
  }
}
