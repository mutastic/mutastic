package com.example.arid;

/** How many orders came in on each day of the week, Monday first. */
public final class Week {
  private Week() {}

  public static int[] perDay(int[] days) {
    int[] counts = new int[7];
    for (int day : days) {
      counts[day]++;
    }
    return counts;
  }
}
