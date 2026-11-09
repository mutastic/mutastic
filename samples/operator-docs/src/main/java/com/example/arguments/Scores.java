package com.example.arguments;

import java.util.Arrays;
import java.util.Collections;

public final class Scores {
  public static int bestOfThree(int first, int second, int third) {
    return Collections.max(Arrays.asList(first, second, third));
  }
}
