package com.example.arid;

import java.util.ArrayList;
import java.util.List;

/** Each value twice, in a list sized for them all. */
public final class Pairs {
  private Pairs() {}

  public static List<Integer> doubled(List<Integer> values) {
    var pairs = new ArrayList<Integer>(values.size() * 2);
    for (int value : values) {
      pairs.add(value);
      pairs.add(value);
    }
    return pairs;
  }
}
