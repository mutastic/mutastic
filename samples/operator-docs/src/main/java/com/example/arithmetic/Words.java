package com.example.arithmetic;

import java.util.ArrayList;
import java.util.List;

public final class Words {
  /// The first `max` of `words`, in order.
  public static List<String> upTo(List<String> words, int max) {
    var kept = new ArrayList<String>();
    int count = 0;
    for (String word : words) {
      if (count == max) {
        break;
      }
      kept.add(word);
      count++;
    }
    return kept;
  }
}
