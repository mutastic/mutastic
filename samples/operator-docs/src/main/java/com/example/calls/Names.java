package com.example.calls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Names {
  public static List<String> sorted(List<String> names) {
    var copy = new ArrayList<>(names);
    Collections.sort(copy);
    return copy;
  }
}
